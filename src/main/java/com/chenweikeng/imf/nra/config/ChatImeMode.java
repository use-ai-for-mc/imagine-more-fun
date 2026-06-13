package com.chenweikeng.imf.nra.config;

/**
 * When to switch the OS input method to English while the chat screen is open.
 *
 * <p>{@link #NON_IF_LANGUAGES} only switches when the active input method's language is not one
 * used on ImagineFun (English, Spanish, French, Japanese, Dutch, Russian, Swedish, Tagalog); {@link
 * #ALWAYS} switches regardless of the active language; {@link #NEVER} leaves the input method
 * alone.
 */
public enum ChatImeMode {
  NON_IF_LANGUAGES,
  ALWAYS,
  NEVER
}
