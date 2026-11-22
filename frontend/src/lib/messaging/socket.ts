export type SocketStatus = 'connecting' | 'online' | 'offline'

export interface SocketOptions {
  url: string
  /** A fresh, device-bound access token (refreshed by the caller when near expiry). */
  getToken: () => Promise<string>
  onFrame: (frame: Record<string, unknown>) => void
  onStatus: (s: SocketStatus) => void
  /** Called for terminal closes (device revoked / account suspended): no reconnect. */
  onTerminated?: (code: number) => void
  WebSocketImpl?: typeof WebSocket
  pingIntervalMs?: number
  reauthIntervalMs?: number
}

/**
 * Gateway connection: the token is sent in the first frame (never in the URL), refreshed on the
 * live socket before it expires, heartbeats every 20 s, exponential-backoff reconnect with jitter.
 * Status becomes "online" only after the gateway's `ready` frame.
 */
export class ChatSocket {
  private ws: WebSocket | null = null
  private stopped = false
  private attempt = 0
  private ready = false
  private timers: ReturnType<typeof setInterval>[] = []
  private retryTimer: ReturnType<typeof setTimeout> | null = null

  constructor(private readonly opts: SocketOptions) {}

  start(): void {
    this.stopped = false
    void this.connect()
  }

  stop(): void {
    this.stopped = true
    if (this.retryTimer) clearTimeout(this.retryTimer)
    this.clearTimers()
    this.ws?.close(1000, 'bye')
    this.ws = null
    this.ready = false
    this.opts.onStatus('offline')
  }

  get isOpen(): boolean {
    return this.ready && this.ws?.readyState === 1
  }

  send(frame: Record<string, unknown>): boolean {
    if (!this.isOpen) return false
    this.ws!.send(JSON.stringify(frame))
    return true
  }

  private async connect(): Promise<void> {
    if (this.stopped) return
    this.opts.onStatus('connecting')
    let token: string
    try {
      token = await this.opts.getToken()
    } catch {
      this.scheduleReconnect()
      return
    }
    if (this.stopped) return
    const Impl = this.opts.WebSocketImpl ?? WebSocket
    const ws = new Impl(this.opts.url)
    this.ws = ws
    ws.onopen = () => ws.send(JSON.stringify({ type: 'auth', token }))
    ws.onmessage = (ev) => {
      let f: Record<string, unknown>
      try {
        f = JSON.parse(String(ev.data))
      } catch {
        return
      }
      if (f.type === 'ready' && !this.ready) {
        this.ready = true
        this.attempt = 0
        this.opts.onStatus('online')
        this.clearTimers()
        this.timers.push(setInterval(() => this.send({ type: 'ping' }), this.opts.pingIntervalMs ?? 20_000))
        this.timers.push(setInterval(() => void this.reauth(), this.opts.reauthIntervalMs ?? 180_000))
      }
      this.opts.onFrame(f)
    }
    ws.onclose = (ev) => {
      this.clearTimers()
      if (this.ws === ws) this.ws = null
      this.ready = false
      this.opts.onStatus('offline')
      if (ev.code === 4403) {
        this.stopped = true
        this.opts.onTerminated?.(ev.code)
        return
      }
      this.scheduleReconnect()
    }
    ws.onerror = () => ws.close()
  }

  private async reauth(): Promise<void> {
    try {
      const token = await this.opts.getToken()
      this.send({ type: 'auth', token })
    } catch {
      /* next attempt / reconnect handles it */
    }
  }

  private scheduleReconnect(): void {
    if (this.stopped) return
    const base = Math.min(30_000, 1000 * 2 ** this.attempt++)
    const delay = base / 2 + Math.random() * (base / 2)
    this.retryTimer = setTimeout(() => void this.connect(), delay)
  }

  private clearTimers(): void {
    for (const t of this.timers) clearInterval(t)
    this.timers = []
  }
}
