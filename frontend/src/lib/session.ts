import { ApiError, type ApiClient } from './api/http'
import type { Profile } from './api/types'
import { ApiKeyDirectory } from './crypto/directory'
import { loadDevice, provisionDevice } from './crypto/keyManager'
import type { KeyStore } from './crypto/keystore'
import { MlsService } from './crypto/mls'
import { SignalProtocolStore } from './crypto/signalStore'
import { ChatEngine } from './messaging/engine'

export interface SessionDeps {
  api: ApiClient
  openStore: (userId: string) => Promise<KeyStore>
  wsUrl: string
  deviceName: string
  WebSocketImpl?: typeof WebSocket
  log?: (msg: string, err?: unknown) => void
  onTerminated?: (reason: string) => void
}

export interface Session {
  user: Profile
  deviceId: string
  engine: ChatEngine
  store: KeyStore
}

/**
 * Ensures this browser has its own registered device with locally generated keys (first use or
 * after revocation), binds the API session to it, then builds the messaging engine.
 */
export async function establishSession(deps: SessionDeps): Promise<Session> {
  const { api } = deps
  const user = await api.me()
  let store = await deps.openStore(user.userId)
  let device = await loadDevice(store)
  if (device?.published && api.tokens.load()?.deviceId !== device.deviceId) {
    try {
      await api.bindDevice(device.deviceId)
    } catch (e) {
      if (e instanceof ApiError && (e.code === 'DEVICE_REVOKED' || e.status === 404)) {
        // This browser's device was revoked: its keys are useless; start over as a new device.
        await store.destroy()
        store = await deps.openStore(user.userId)
        device = undefined
      } else {
        throw e
      }
    }
  }
  if (!device?.published) {
    const signal = new SignalProtocolStore(store)
    const directory = new ApiKeyDirectory(api)
    device = await provisionDevice(api, store, signal,
      (deviceId) => new MlsService(store, directory, signal, { userId: user.userId, deviceId }), user.userId, deps.deviceName)
  }
  const engine = new ChatEngine({
    api, store, wsUrl: deps.wsUrl, WebSocketImpl: deps.WebSocketImpl, log: deps.log, onTerminated: deps.onTerminated,
    me: { userId: user.userId, username: user.username, deviceId: device.deviceId },
  })
  engine.rememberProfile(user)
  return { user, deviceId: device.deviceId, engine, store }
}

export async function signIn(deps: SessionDeps, mode: 'login' | 'register', login: string, password: string,
  extra?: { email?: string; displayName?: string }): Promise<Session> {
  if (mode === 'register') await deps.api.register(extra?.email ?? '', login, password, extra?.displayName)
  else await deps.api.login(login, password)
  return establishSession(deps)
}
