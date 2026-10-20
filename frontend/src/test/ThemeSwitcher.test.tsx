import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ThemeSwitcher } from '../components/ThemeSwitcher'

describe('ThemeSwitcher', () => {
  beforeEach(() => {
    // Clear localStorage and document attributes before each test
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
  })

  it('renders the theme switcher button', () => {
    render(<ThemeSwitcher />)
    const button = screen.getByRole('switch', { name: 'Dark theme' })
    expect(button).toBeInTheDocument()
    expect(button).toHaveAttribute('aria-checked', 'false')
  })

  it('toggles theme when clicked', () => {
    render(<ThemeSwitcher />)
    const button = screen.getByRole('switch', { name: 'Dark theme' })

    // Click to switch to dark mode
    fireEvent.click(button)
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    expect(button).toHaveAttribute('aria-checked', 'true')
    expect(localStorage.getItem('theme')).toBe('dark')

    // Click again to switch back to light mode
    fireEvent.click(button)
    expect(document.documentElement).not.toHaveAttribute('data-theme')
    expect(button).toHaveAttribute('aria-checked', 'false')
    expect(localStorage.getItem('theme')).toBe('light')
  })

  it('calls onChange callback when theme changes', () => {
    const onChange = vi.fn()
    render(<ThemeSwitcher onChange={onChange} />)
    const button = screen.getByRole('switch', { name: 'Dark theme' })

    fireEvent.click(button)
    expect(onChange).toHaveBeenCalledWith(true)

    fireEvent.click(button)
    expect(onChange).toHaveBeenCalledWith(false)
  })
})
