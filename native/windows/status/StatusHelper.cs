// StatusHelper.cs
// Windows native helper that shows a countdown (or any short text) in the
// notification area via NotifyIcon. Text is rendered into a dynamic bitmap so
// the digits are always visible, not hidden behind a hover tooltip.
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
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows.Forms;

class StatusHelper : ApplicationContext
{
    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern bool DestroyIcon(IntPtr handle);

    private readonly NotifyIcon notifyIcon;
    private readonly Control marshaller;
    private IntPtr currentIconHandle = IntPtr.Zero;

    public StatusHelper()
    {
        // UI-thread marshaller. We CANNOT capture SynchronizationContext.Current here: this
        // constructor runs as the argument to Application.Run(new StatusHelper()), which installs
        // the WindowsFormsSynchronizationContext only AFTER constructing us — so Current is null
        // at this point and every SetText post would throw NullReferenceException, leaving the
        // tray icon frozen on its initial "—:—". Instead we create a hidden control and force its
        // HWND on this (UI) thread now; BeginInvoke then posts work to that handle's message
        // queue, which Application.Run pumps. This is independent of sync-context install timing.
        marshaller = new Control();
        _ = marshaller.Handle; // force handle creation on the UI thread

        notifyIcon = new NotifyIcon
        {
            Icon = RenderIcon("—:—"),
            Text = "ImagineMoreFun",
            Visible = true
        };

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

    /// <summary>
    /// Compacts a duration string to at most three glyphs for the icon bitmap. The tray icon is
    /// displayed at 16x16 device pixels (100% scaling), so anything longer renders unreadably
    /// small; the tooltip keeps the full text. "4m12s" becomes "4m", "59s" stays "59s".
    /// </summary>
    private static string CompactIconText(string text)
    {
        if (text.Length <= 3) return text;
        int digits = 0;
        while (digits < text.Length && char.IsDigit(text[digits])) digits++;
        // Leading number plus its unit letter ("12m34s" -> "12m"), else just truncate.
        if (digits > 0 && digits < text.Length) return text.Substring(0, digits + 1);
        return text.Substring(0, 3);
    }

    private Icon RenderIcon(string text)
    {
        const int size = 32;
        string iconText = CompactIconText(text);
        using var bmp = new Bitmap(size, size);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAlias;
            g.Clear(Color.Transparent);

            using var fg = new SolidBrush(Color.White);
            using var shadow = new SolidBrush(Color.FromArgb(180, 0, 0, 0));
            // GenericTypographic drops GDI+'s built-in side bearings so the glyphs can
            // actually reach the canvas edges.
            StringFormat fmt = StringFormat.GenericTypographic;

            // Largest font that fits: measure once at a reference size, scale proportionally.
            // A fixed small size wastes most of the canvas, and the canvas itself is shown at
            // 16x16 — every wasted pixel halves again on screen.
            const float referenceSize = 20f;
            float fontSize;
            using (var trial = new Font("Segoe UI", referenceSize, FontStyle.Bold, GraphicsUnit.Pixel))
            {
                SizeF m = g.MeasureString(iconText, trial, int.MaxValue, fmt);
                float scale = Math.Min((size - 1) / m.Width, size / m.Height);
                fontSize = Math.Max(8f, referenceSize * scale);
            }

            using var font = new Font("Segoe UI", fontSize, FontStyle.Bold, GraphicsUnit.Pixel);
            SizeF measured = g.MeasureString(iconText, font, int.MaxValue, fmt);
            float x = (size - measured.Width) / 2f;
            float y = (size - measured.Height) / 2f;

            // Shadow + foreground for readability on any taskbar color.
            g.DrawString(iconText, font, shadow, x + 1, y + 1, fmt);
            g.DrawString(iconText, font, fg, x, y, fmt);
        }

        IntPtr hIcon = bmp.GetHicon();
        Icon icon = Icon.FromHandle(hIcon);
        // HICON leak guard: destroy the previous handle, keep the new one.
        IntPtr prev = currentIconHandle;
        currentIconHandle = hIcon;
        if (prev != IntPtr.Zero) DestroyIcon(prev);
        return icon;
    }

    private void SetText(string text)
    {
        RunOnUi(() =>
        {
            var old = notifyIcon.Icon;
            notifyIcon.Icon = RenderIcon(text);
            old?.Dispose();
            notifyIcon.Text = string.IsNullOrEmpty(text) ? "ImagineMoreFun" : $"Ride: {text}";
        });
    }

    private void Quit()
    {
        RunOnUi(() =>
        {
            notifyIcon.Visible = false;
            notifyIcon.Dispose();
            marshaller.Dispose();
            if (currentIconHandle != IntPtr.Zero) DestroyIcon(currentIconHandle);
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
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new StatusHelper());
    }
}
