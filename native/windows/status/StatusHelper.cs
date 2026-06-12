// StatusHelper.cs
// Windows native helper that shows the ride countdown in a borderless topmost
// window glued next to the taskbar's tray area (see TaskbarOverlay below).
// A NotifyIcon tray icon was tried first and removed: its 16x16 canvas can
// never render a readable countdown, and next to the overlay it was a
// duplicate (user decision 2026-06-12).
//
// Prerequisites:
//   - .NET 8 SDK (cross-build from macOS requires EnableWindowsTargeting)
//   - .NET 8 Desktop Runtime on the target machine (WinForms)
//
// Build:
//   dotnet publish -c Release -r win-x64 --no-self-contained
//
// Protocol: Same as macOS helper (see StatusHelper.swift)

using System;
using System.Drawing;
using System.Drawing.Text;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

class StatusHelper : ApplicationContext
{
    private readonly TaskbarOverlay overlay;
    private readonly Control marshaller;

    public StatusHelper()
    {
        // UI-thread marshaller. We CANNOT capture SynchronizationContext.Current here: this
        // constructor runs as the argument to Application.Run(new StatusHelper()), which installs
        // the WindowsFormsSynchronizationContext only AFTER constructing us — so Current is null
        // at this point and every SetText post would throw NullReferenceException, leaving the
        // countdown frozen. Instead we create a hidden control and force its HWND on this (UI)
        // thread now; BeginInvoke then posts work to that handle's message queue, which
        // Application.Run pumps. This is independent of sync-context install timing.
        marshaller = new Control();
        _ = marshaller.Handle; // force handle creation on the UI thread

        overlay = new TaskbarOverlay();

        Task.Run(ReadLoop);
        WriteLine("{\"type\":\"ready\"}");
    }

    /** Runs an action on the UI thread via the marshalling control's message queue. */
    private void RunOnUi(Action action)
    {
        if (marshaller.IsHandleCreated)
        {
            marshaller.BeginInvoke(action);
        }
    }

    private void SetText(string text)
    {
        RunOnUi(() => overlay.SetCountdown(text));
    }

    private void Quit()
    {
        RunOnUi(() =>
        {
            overlay.Dispose();
            marshaller.Dispose();
            Application.Exit();
        });
    }

    private void ReadLoop()
    {
        string? line;
        while ((line = Console.In.ReadLine()) != null)
        {
            if (line.Length == 0) continue;
            try
            {
                using var doc = JsonDocument.Parse(line);
                var root = doc.RootElement;
                if (!root.TryGetProperty("cmd", out var cmdEl)) continue;
                string? cmd = cmdEl.GetString();
                if (cmd == "set" && root.TryGetProperty("text", out var txtEl))
                {
                    SetText(txtEl.GetString() ?? "");
                }
                else if (cmd == "quit")
                {
                    Quit();
                    return;
                }
            }
            catch (Exception ex)
            {
                WriteLine($"{{\"type\":\"error\",\"message\":{JsonSerializer.Serialize(ex.Message)}}}");
            }
        }
        // stdin closed — parent died or disconnected; exit.
        Quit();
    }

    private static void WriteLine(string line)
    {
        Console.Out.WriteLine(line);
        Console.Out.Flush();
    }

    [STAThread]
    static void Main()
    {
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        // A faceless background helper must never show the WinForms crash dialog over the
        // game; route UI-thread exceptions to the parent's log via the stdout protocol.
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (s, e) =>
            WriteLine(
                $"{{\"type\":\"error\",\"message\":{JsonSerializer.Serialize(e.Exception.ToString())}}}");
        Application.Run(new StatusHelper());
    }
}

/// <summary>
/// Readable countdown glued next to the taskbar's tray area (TrafficMonitor-taskbar look). The
/// notification area only accepts a 16x16 icon, so for legible digits we float a borderless
/// topmost window over the taskbar rectangle, ElevenClock-style: positioned from
/// Shell_TrayWnd/TrayNotifyWnd coordinates and re-glued by a 1s timer, NOT SetParent'ed into
/// Explorer's window tree — so an Explorer restart or taskbar redesign can never hang or orphan
/// us; worst case the window floats a few pixels off. Click-through (WS_EX_TRANSPARENT + layered)
/// and non-activating, so it can never steal focus from the game. Visible only while a ride is
/// active (non-empty text); hidden over exclusive-fullscreen apps by nature of being a normal
/// topmost window.
/// </summary>
class TaskbarOverlay : Form
{
    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern IntPtr FindWindow(string className, string? windowName);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern IntPtr FindWindowEx(
        IntPtr parent, IntPtr childAfter, string className, string? windowName);

    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);

    [DllImport("user32.dll")]
    private static extern bool SetWindowPos(
        IntPtr hwnd, IntPtr insertAfter, int x, int y, int w, int h, uint flags);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(
        IntPtr hwnd, int attribute, ref int value, int size);

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left, Top, Right, Bottom;
    }

    private const int WS_EX_TRANSPARENT = 0x20;
    private const int WS_EX_TOOLWINDOW = 0x80;
    private const int WS_EX_TOPMOST = 0x8;
    private const int WS_EX_NOACTIVATE = 0x08000000;
    private static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
    private const uint SWP_NOACTIVATE = 0x0010;
    private const uint SWP_SHOWWINDOW = 0x0040;
    private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private const int DWMWCP_ROUNDSMALL = 3;

    private readonly System.Windows.Forms.Timer glueTimer;
    private string text = "";
    private bool darkTheme = true;

    public TaskbarOverlay()
    {
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.Manual;
        MinimumSize = new Size(1, 1);
        SetStyle(
            ControlStyles.AllPaintingInWmPaint
                | ControlStyles.OptimizedDoubleBuffer
                | ControlStyles.UserPaint
                | ControlStyles.ResizeRedraw,
            true);
        // Forces WS_EX_LAYERED, which WS_EX_TRANSPARENT click-through requires; the slight
        // translucency also blends the pill into any taskbar color.
        Opacity = 0.96;
        glueTimer = new System.Windows.Forms.Timer { Interval = 1000 };
        glueTimer.Tick += (s, e) => Reposition();
    }

    /** Keeps Show() from stealing focus (e.g. from the game) — pairs with WS_EX_NOACTIVATE. */
    protected override bool ShowWithoutActivation => true;

    protected override CreateParams CreateParams
    {
        get
        {
            CreateParams cp = base.CreateParams;
            cp.ExStyle |= WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TRANSPARENT;
            return cp;
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // Win11 rounded corners; harmlessly fails (E_INVALIDARG) on Win10.
        try
        {
            int pref = DWMWCP_ROUNDSMALL;
            DwmSetWindowAttribute(Handle, DWMWA_WINDOW_CORNER_PREFERENCE, ref pref, sizeof(int));
        }
        catch (Exception)
        {
        }
    }

    public void SetCountdown(string value)
    {
        text = value;
        if (string.IsNullOrEmpty(value))
        {
            glueTimer.Stop();
            if (Visible) Hide();
            return;
        }
        ReadTheme();
        Reposition();
        if (!Visible) Show();
        glueTimer.Start();
        Invalidate();
    }

    /// <summary>
    /// Re-glues the window next to the tray notification area. Runs every second so it follows
    /// taskbar moves, auto-hide slides, and resolution changes; coordinates are re-read each time
    /// rather than cached.
    /// </summary>
    private void Reposition()
    {
        int boxH = 38, boxW;
        int x, y;
        IntPtr taskbar = FindWindow("Shell_TrayWnd", null);
        RECT tb;
        if (taskbar != IntPtr.Zero && GetWindowRect(taskbar, out tb)
            && tb.Right - tb.Left > tb.Bottom - tb.Top)
        {
            int tbHeight = tb.Bottom - tb.Top;
            boxH = Math.Clamp(tbHeight - 10, 22, 40);
            boxW = MeasureWidth(boxH);
            // Sit just left of the tray icons, like TrafficMonitor's taskbar mode.
            int rightEdge = tb.Right - 8;
            IntPtr tray = FindWindowEx(taskbar, IntPtr.Zero, "TrayNotifyWnd", null);
            if (tray != IntPtr.Zero && GetWindowRect(tray, out RECT tr) && tr.Left > tb.Left)
            {
                rightEdge = tr.Left - 8;
            }
            x = rightEdge - boxW;
            y = tb.Top + (tbHeight - boxH) / 2;
        }
        else
        {
            // No taskbar found, or it is docked vertically: fall back to the bottom-right corner
            // of the primary working area, which excludes the taskbar wherever it is docked.
            boxW = MeasureWidth(boxH);
            Rectangle wa = Screen.PrimaryScreen?.WorkingArea ?? new Rectangle(0, 0, 1280, 720);
            x = wa.Right - boxW - 12;
            y = wa.Bottom - boxH - 12;
        }
        SetWindowPos(Handle, HWND_TOPMOST, x, y, boxW, boxH, SWP_NOACTIVATE | SWP_SHOWWINDOW);
    }

    private int MeasureWidth(int boxH)
    {
        using Font font = OverlayFont(boxH);
        return TextRenderer.MeasureText(text, font).Width + 22;
    }

    private static Font OverlayFont(int boxH)
    {
        return new Font(
            "Segoe UI", Math.Max(12f, boxH * 0.55f), FontStyle.Bold, GraphicsUnit.Pixel);
    }

    private void ReadTheme()
    {
        // The taskbar follows the SYSTEM theme value, not the apps one.
        try
        {
            object? v = Registry.GetValue(
                "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "SystemUsesLightTheme",
                0);
            darkTheme = !(v is int i && i == 1);
        }
        catch (Exception)
        {
            darkTheme = true;
        }
        BackColor = darkTheme ? Color.FromArgb(28, 28, 28) : Color.FromArgb(238, 238, 238);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        Color fg = darkTheme ? Color.White : Color.FromArgb(20, 20, 20);
        using Font font = OverlayFont(Height);
        TextRenderer.DrawText(
            e.Graphics,
            text,
            font,
            ClientRectangle,
            fg,
            TextFormatFlags.HorizontalCenter
                | TextFormatFlags.VerticalCenter
                | TextFormatFlags.SingleLine);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            glueTimer.Dispose();
        }
        base.Dispose(disposing);
    }
}
