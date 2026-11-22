import type { ApiClient } from '../api/http'
import type { DeviceIdentity, UserBundle } from '../api/types'

/** Server-side public key directory. UNTRUSTED: every key from it is verified locally before use. */
export interface KeyDirectory {
  claimBundle(userId: string, includeMls: boolean, deviceIds?: string[]): Promise<UserBundle>
  identities(userId: string, fresh?: boolean): Promise<DeviceIdentity[]>
}

export class ApiKeyDirectory implements KeyDirectory {
  private cache = new Map<string, { at: number; value: DeviceIdentity[] }>()

  constructor(private readonly api: ApiClient, private readonly ttlMs = 60_000) {}

  claimBundle(userId: string, includeMls: boolean, deviceIds?: string[]): Promise<UserBundle> {
    return this.api.claimBundle(userId, includeMls, deviceIds)
  }

  async identities(userId: string, fresh = false): Promise<DeviceIdentity[]> {
    const hit = this.cache.get(userId)
    if (!fresh && hit && Date.now() - hit.at < this.ttlMs) return hit.value
    const value = await this.api.identities(userId)
    this.cache.set(userId, { at: Date.now(), value })
    return value
  }
}
