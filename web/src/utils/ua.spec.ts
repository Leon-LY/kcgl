import { beforeEach, describe, expect, it } from 'vitest'
import { detectShell, resolveShell, saveShellPreference } from './ua'

const IPHONE_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1'
const ANDROID_UA =
  'Mozilla/5.0 (Linux; Android 15; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36'
const WINDOWS_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36'
const MAC_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15'

describe('detectShell', () => {
  it('classifies iPhone/Android mobile UAs as mobile', () => {
    expect(detectShell(IPHONE_UA)).toBe('mobile')
    expect(detectShell(ANDROID_UA)).toBe('mobile')
  })

  it('classifies Windows/Mac desktop UAs as desktop', () => {
    expect(detectShell(WINDOWS_UA)).toBe('desktop')
    expect(detectShell(MAC_UA)).toBe('desktop')
  })
})

describe('resolveShell', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('gives the one-time query override precedence over saved preference and UA (shared links carry no personal preference)', () => {
    localStorage.setItem('kcgl-shell', 'desktop')
    expect(resolveShell(IPHONE_UA, new URLSearchParams('shell=desktop'))).toBe('desktop')
    expect(resolveShell(WINDOWS_UA, new URLSearchParams('shell=mobile'))).toBe('mobile')
  })

  it('prefers the saved local preference next', () => {
    localStorage.setItem('kcgl-shell', 'mobile')
    expect(resolveShell(WINDOWS_UA, null)).toBe('mobile')
    localStorage.setItem('kcgl-shell', 'desktop')
    expect(resolveShell(IPHONE_UA, null)).toBe('desktop')
  })

  it('falls back to UA detection when no preference exists', () => {
    expect(resolveShell(IPHONE_UA, null)).toBe('mobile')
    expect(resolveShell(WINDOWS_UA, null)).toBe('desktop')
  })

  it('ignores invalid query values without throwing', () => {
    expect(resolveShell(IPHONE_UA, new URLSearchParams('shell=tablet'))).toBe('mobile')
    expect(resolveShell(IPHONE_UA, new URLSearchParams('shell='))).toBe('mobile')
  })
})

describe('saveShellPreference', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('persists the preference for restoration on later visits', () => {
    saveShellPreference('desktop')
    expect(localStorage.getItem('kcgl-shell')).toBe('desktop')
  })
})
