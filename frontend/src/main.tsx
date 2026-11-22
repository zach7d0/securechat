import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './index.css'
import { loadOnboardingConfig } from './lib/onboarding'
import { initAppearance } from './lib/theme'

initAppearance()
void loadOnboardingConfig()

if (!window.isSecureContext || !crypto.subtle) {
  document.getElementById('root')!.innerHTML =
    '<p style="padding:2rem;font-family:sans-serif">SecureChat needs a secure context (HTTPS or localhost) for WebCrypto.</p>'
} else {
  createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
}
