import argparse
import json
import queue
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

import pygame
import serial


MAGIC = b"\xA5\x5A"
VERSION = 2
FRAME_RATE_HZ = 20

# Packet:
# magic[2], version u8, seq u16, buttons u8,
# lx i16, ly i16, rx i16, ry i16, lt u16, rt u16, checksum u8
PACK_FMT_NO_CHECKSUM = "<2sBHBhhhhHH"
FRAME_LEN = struct.calcsize(PACK_FMT_NO_CHECKSUM) + 1

BTN_CROSS = 1 << 0
BTN_CIRCLE = 1 << 1
BTN_SQUARE = 1 << 2
BTN_TRIANGLE = 1 << 3

# Sent only while the dashboard "Driver 1" button is pulsing.
# On FTC-style Logitech controllers this is Start + A.
BTN_A = BTN_CROSS
BTN_START = 1 << 4
BTN_L1 = 1 << 5
BTN_R1 = 1 << 6

EVENT_BACKLOG = 500
STALE_AFTER_S = 0.35


INDEX_HTML = r"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>FTC LoRa Control</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #101214;
      --panel: #171b1f;
      --panel-2: #1d2328;
      --border: #2e363d;
      --text: #eff3f5;
      --muted: #9daab3;
      --accent: #48c78e;
      --accent-2: #5aa9ff;
      --warn: #ffcc66;
      --bad: #ff6b6b;
      --shadow: 0 18px 45px rgba(0, 0, 0, 0.28);
    }

    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      background: var(--bg);
      color: var(--text);
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }

    .app {
      width: min(1280px, calc(100vw - 32px));
      margin: 0 auto;
      padding: 24px 0;
    }

    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 20px;
      margin-bottom: 18px;
    }

    h1 {
      margin: 0;
      font-size: 28px;
      font-weight: 720;
      letter-spacing: 0;
    }

    .subtitle {
      margin-top: 5px;
      color: var(--muted);
      font-size: 14px;
    }

    .status-row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      justify-content: flex-end;
    }

    .pill {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      border: 1px solid var(--border);
      background: var(--panel);
      border-radius: 999px;
      padding: 8px 12px;
      color: var(--muted);
      font-size: 13px;
      white-space: nowrap;
    }

    .dot {
      width: 8px;
      height: 8px;
      border-radius: 999px;
      background: var(--bad);
      box-shadow: 0 0 0 3px rgba(255, 107, 107, 0.12);
    }

    .ok .dot {
      background: var(--accent);
      box-shadow: 0 0 0 3px rgba(72, 199, 142, 0.12);
    }

    .warn .dot {
      background: var(--warn);
      box-shadow: 0 0 0 3px rgba(255, 204, 102, 0.12);
    }

    main {
      display: grid;
      grid-template-columns: minmax(360px, 0.82fr) minmax(420px, 1.18fr);
      gap: 16px;
    }

    section {
      min-width: 0;
      border: 1px solid var(--border);
      background: var(--panel);
      border-radius: 8px;
      box-shadow: var(--shadow);
    }

    .section-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      padding: 14px 16px;
      border-bottom: 1px solid var(--border);
    }

    h2 {
      margin: 0;
      font-size: 15px;
      font-weight: 680;
      letter-spacing: 0;
    }

    .content { padding: 16px; }

    .metrics {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 10px;
      margin-bottom: 16px;
    }

    .metric {
      background: var(--panel-2);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 12px;
    }

    .label {
      color: var(--muted);
      font-size: 12px;
      margin-bottom: 6px;
    }

    .value {
      font-variant-numeric: tabular-nums;
      font-size: 22px;
      font-weight: 720;
    }

    .controls {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
    }

    .stick-wrap {
      background: var(--panel-2);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 12px;
    }

    .stick {
      position: relative;
      width: 100%;
      aspect-ratio: 1;
      border-radius: 8px;
      background:
        linear-gradient(to right, transparent calc(50% - 1px), #38424a 50%, transparent calc(50% + 1px)),
        linear-gradient(to bottom, transparent calc(50% - 1px), #38424a 50%, transparent calc(50% + 1px)),
        #11161a;
      overflow: hidden;
    }

    .knob {
      position: absolute;
      left: 50%;
      top: 50%;
      width: 22px;
      height: 22px;
      border-radius: 999px;
      background: var(--accent-2);
      border: 2px solid #d8ecff;
      transform: translate(-50%, -50%);
      box-shadow: 0 0 18px rgba(90, 169, 255, 0.55);
    }

    .stick-readout {
      display: flex;
      justify-content: space-between;
      gap: 8px;
      color: var(--muted);
      font-size: 12px;
      margin-top: 10px;
      font-variant-numeric: tabular-nums;
    }

    .bars {
      display: grid;
      gap: 12px;
      margin-top: 16px;
    }

    .bar-row {
      display: grid;
      grid-template-columns: 92px 1fr 52px;
      align-items: center;
      gap: 10px;
      color: var(--muted);
      font-size: 13px;
    }

    .bar {
      height: 10px;
      border-radius: 999px;
      overflow: hidden;
      background: #101417;
      border: 1px solid var(--border);
    }

    .fill {
      height: 100%;
      width: 0%;
      background: var(--accent);
    }

    .buttons {
      display: grid;
      grid-template-columns: repeat(6, minmax(0, 1fr));
      gap: 10px;
      margin-top: 16px;
    }

    .btn-state {
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 12px 8px;
      text-align: center;
      color: var(--muted);
      background: var(--panel-2);
      font-size: 13px;
      font-weight: 650;
    }

    .btn-state.active {
      color: #07130d;
      border-color: var(--accent);
      background: var(--accent);
    }

    .actions {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
    }

    button {
      appearance: none;
      border: 1px solid var(--border);
      background: var(--panel-2);
      color: var(--text);
      border-radius: 8px;
      padding: 9px 12px;
      font-weight: 680;
      cursor: pointer;
    }

    button:hover { border-color: #53616b; }
    button.primary {
      background: var(--accent);
      color: #06120c;
      border-color: var(--accent);
    }

    .terminal {
      height: 604px;
      overflow: auto;
      background: #080a0c;
      border-radius: 8px;
      padding: 12px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
      font-size: 12px;
      line-height: 1.45;
      color: #d7e0e5;
      white-space: pre;
    }

    .terminal .muted { color: #84919a; }
    .terminal .pulse { color: #8ee8b8; }

    @media (max-width: 980px) {
      header { align-items: flex-start; flex-direction: column; }
      .status-row { justify-content: flex-start; }
      main { grid-template-columns: 1fr; }
    }

    @media (max-width: 620px) {
      .app { width: min(100vw - 20px, 1280px); padding: 14px 0; }
      .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .controls { grid-template-columns: 1fr; }
      .buttons { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .terminal { height: 420px; }
    }
  </style>
</head>
<body>
  <div class="app">
    <header>
      <div>
        <h1>FTC LoRa Control</h1>
        <div class="subtitle" id="subtitle">Waiting for telemetry...</div>
      </div>
      <div class="status-row">
        <div class="pill" id="gamepadPill"><span class="dot"></span><span>Gamepad</span></div>
        <div class="pill" id="serialPill"><span class="dot"></span><span>Serial</span></div>
        <div class="pill" id="streamPill"><span class="dot"></span><span>Dashboard</span></div>
      </div>
    </header>

    <main>
      <section>
        <div class="section-head">
          <h2>Live State</h2>
          <div class="actions">
            <button class="primary" id="driver1Btn">Driver 1: Start + A</button>
          </div>
        </div>
        <div class="content">
          <div class="metrics">
            <div class="metric"><div class="label">Sequence</div><div class="value" id="seq">-</div></div>
            <div class="metric"><div class="label">Rate</div><div class="value" id="rate">-</div></div>
            <div class="metric"><div class="label">Sent</div><div class="value" id="sent">-</div></div>
            <div class="metric"><div class="label">Age</div><div class="value" id="age">-</div></div>
          </div>

          <div class="controls">
            <div class="stick-wrap">
              <div class="label">Left Stick</div>
              <div class="stick"><div class="knob" id="leftKnob"></div></div>
              <div class="stick-readout"><span id="lx">x 0</span><span id="ly">y 0</span></div>
            </div>
            <div class="stick-wrap">
              <div class="label">Right Stick</div>
              <div class="stick"><div class="knob" id="rightKnob"></div></div>
              <div class="stick-readout"><span id="rx">x 0</span><span id="ry">y 0</span></div>
            </div>
          </div>

          <div class="bars">
            <div class="bar-row"><span>Left Trigger</span><div class="bar"><div class="fill" id="ltFill"></div></div><span id="lt">0</span></div>
            <div class="bar-row"><span>Right Trigger</span><div class="bar"><div class="fill" id="rtFill"></div></div><span id="rt">0</span></div>
          </div>

          <div class="buttons">
            <div class="btn-state" id="cross">Cross / A</div>
            <div class="btn-state" id="circle">Circle / B</div>
            <div class="btn-state" id="square">Square / X</div>
            <div class="btn-state" id="triangle">Triangle / Y</div>
            <div class="btn-state" id="leftBumper">L1 / LB</div>
            <div class="btn-state" id="rightBumper">R1 / RB</div>
          </div>
        </div>
      </section>

      <section>
        <div class="section-head">
          <h2>Transmit Log</h2>
          <div class="actions">
            <button id="clearBtn">Clear</button>
            <button id="pauseBtn">Pause</button>
          </div>
        </div>
        <div class="content">
          <div class="terminal" id="terminal"></div>
        </div>
      </section>
    </main>
  </div>

  <script>
    const el = id => document.getElementById(id);
    const term = el('terminal');
    let paused = false;
    let lastEventAt = 0;

    function setPill(id, state, text) {
      const node = el(id);
      node.classList.remove('ok', 'warn');
      if (state === 'ok') node.classList.add('ok');
      if (state === 'warn') node.classList.add('warn');
      node.querySelector('span:last-child').textContent = text;
    }

    function setStick(id, x, y) {
      const knob = el(id);
      const px = Math.max(-1000, Math.min(1000, x)) / 1000 * 42;
      const py = Math.max(-1000, Math.min(1000, y)) / 1000 * 42;
      knob.style.left = `${50 + px}%`;
      knob.style.top = `${50 + py}%`;
    }

    function setButton(id, active) {
      el(id).classList.toggle('active', Boolean(active));
    }

    function appendLog(frame) {
      if (paused) return;
      const line = document.createElement('div');
      if (frame.injected_driver1) line.className = 'pulse';
      line.textContent =
        `seq=${String(frame.seq).padStart(5, ' ')} ` +
        `lx=${String(frame.lx).padStart(5, ' ')} ly=${String(frame.ly).padStart(5, ' ')} ` +
        `rx=${String(frame.rx).padStart(5, ' ')} ry=${String(frame.ry).padStart(5, ' ')} ` +
        `lt=${String(frame.lt).padStart(4, ' ')} rt=${String(frame.rt).padStart(4, ' ')} ` +
        `cross=${frame.cross ? 1 : 0} circle=${frame.circle ? 1 : 0} ` +
        `square=${frame.square ? 1 : 0} triangle=${frame.triangle ? 1 : 0} ` +
        `l1=${frame.left_bumper ? 1 : 0} r1=${frame.right_bumper ? 1 : 0} ` +
        `serial=${frame.serial_ok ? 'ok' : 'down'} gamepad=${frame.gamepad_ok ? 'ok' : 'down'}`;
      term.appendChild(line);
      while (term.childNodes.length > 2500) term.removeChild(term.firstChild);
      term.scrollTop = term.scrollHeight;
    }

    function updateFrame(frame) {
      lastEventAt = Date.now();
      el('subtitle').textContent = `${frame.controller_name || 'Controller'} -> ${frame.port} @ ${frame.baud}`;
      el('seq').textContent = frame.seq;
      el('rate').textContent = `${frame.hz.toFixed(1)}`;
      el('sent').textContent = frame.sent_count;
      el('age').textContent = `${frame.age_ms} ms`;

      el('lx').textContent = `x ${frame.lx}`;
      el('ly').textContent = `y ${frame.ly}`;
      el('rx').textContent = `x ${frame.rx}`;
      el('ry').textContent = `y ${frame.ry}`;
      setStick('leftKnob', frame.lx, frame.ly);
      setStick('rightKnob', frame.rx, frame.ry);

      el('lt').textContent = frame.lt;
      el('rt').textContent = frame.rt;
      el('ltFill').style.width = `${Math.max(0, Math.min(100, frame.lt / 10))}%`;
      el('rtFill').style.width = `${Math.max(0, Math.min(100, frame.rt / 10))}%`;

      setButton('cross', frame.cross);
      setButton('circle', frame.circle);
      setButton('square', frame.square);
      setButton('triangle', frame.triangle);
      setButton('leftBumper', frame.left_bumper);
      setButton('rightBumper', frame.right_bumper);

      setPill('gamepadPill', frame.gamepad_ok ? 'ok' : 'bad', frame.gamepad_ok ? 'Gamepad OK' : 'Gamepad Down');
      setPill('serialPill', frame.serial_ok ? 'ok' : 'bad', frame.serial_ok ? 'Serial OK' : 'Serial Down');
      setPill('streamPill', 'ok', 'Dashboard Live');
      appendLog(frame);
    }

    function connectEvents() {
      const source = new EventSource('/events');
      source.onopen = () => setPill('streamPill', 'ok', 'Dashboard Live');
      source.onerror = () => setPill('streamPill', 'warn', 'Reconnecting');
      source.addEventListener('frame', e => updateFrame(JSON.parse(e.data)));
      source.addEventListener('notice', e => {
        const line = document.createElement('div');
        line.className = 'muted';
        line.textContent = JSON.parse(e.data).message;
        term.appendChild(line);
      });
    }

    el('driver1Btn').addEventListener('click', async () => {
      el('driver1Btn').disabled = true;
      try {
        await fetch('/api/driver1', { method: 'POST' });
      } finally {
        setTimeout(() => { el('driver1Btn').disabled = false; }, 900);
      }
    });

    el('clearBtn').addEventListener('click', () => term.textContent = '');
    el('pauseBtn').addEventListener('click', () => {
      paused = !paused;
      el('pauseBtn').textContent = paused ? 'Resume' : 'Pause';
    });

    setInterval(() => {
      if (!lastEventAt) return;
      if (Date.now() - lastEventAt > 900) setPill('streamPill', 'warn', 'No Recent Frames');
    }, 250);

    connectEvents();
  </script>
</body>
</html>
"""


class SharedState:
    def __init__(self):
        self.lock = threading.Lock()
        self.clients = set()
        self.latest = None
        self.sent_count = 0
        self.serial_ok = False
        self.gamepad_ok = False
        self.controller_name = ""
        self.port = ""
        self.baud = 0
        self.hz = 0.0
        self.driver1_until = 0.0

    def snapshot(self):
        with self.lock:
            return dict(self.latest) if self.latest else None

    def add_client(self):
        q = queue.Queue(maxsize=EVENT_BACKLOG)
        with self.lock:
            self.clients.add(q)
        return q

    def remove_client(self, q):
        with self.lock:
            self.clients.discard(q)

    def publish(self, event, payload):
        data = json.dumps(payload, separators=(",", ":"))
        dead = []
        with self.lock:
            for q in self.clients:
                try:
                    q.put_nowait((event, data))
                except queue.Full:
                    dead.append(q)
            for q in dead:
                self.clients.discard(q)

    def pulse_driver1(self, seconds=0.7):
        with self.lock:
            self.driver1_until = max(self.driver1_until, time.monotonic() + seconds)

    def driver1_active(self):
        with self.lock:
            return time.monotonic() < self.driver1_until


def apply_deadband(value, deadband=0.06):
    if abs(value) < deadband:
        return 0.0
    return value


def clamp(value, low, high):
    return max(low, min(high, value))


def stick_to_i16(value):
    value = clamp(value, -1.0, 1.0)
    return int(round(value * 1000))


def trigger_to_u16(value):
    normalized = (clamp(value, -1.0, 1.0) + 1.0) / 2.0
    return int(round(normalized * 1000))


def checksum(payload):
    c = 0
    for b in payload:
        c ^= b
    return c


def read_controller_state(joystick, l1_button, r1_button):
    pygame.event.pump()

    lx = stick_to_i16(apply_deadband(joystick.get_axis(0)))
    ly = stick_to_i16(apply_deadband(joystick.get_axis(1)))
    rx = stick_to_i16(apply_deadband(joystick.get_axis(2)))
    ry = stick_to_i16(apply_deadband(joystick.get_axis(3)))
    lt = trigger_to_u16(joystick.get_axis(4))
    rt = trigger_to_u16(joystick.get_axis(5))

    buttons = 0
    if joystick.get_button(0):
      buttons |= BTN_CROSS
    if joystick.get_button(1):
      buttons |= BTN_CIRCLE
    if joystick.get_button(2):
      buttons |= BTN_SQUARE
    if joystick.get_button(3):
      buttons |= BTN_TRIANGLE
    if l1_button < joystick.get_numbuttons() and joystick.get_button(l1_button):
      buttons |= BTN_L1
    if r1_button < joystick.get_numbuttons() and joystick.get_button(r1_button):
      buttons |= BTN_R1

    return {
        "lx": lx,
        "ly": ly,
        "rx": rx,
        "ry": ry,
        "lt": lt,
        "rt": rt,
        "buttons": buttons,
    }


def build_frame(seq, state):
    frame_without_checksum = struct.pack(
        PACK_FMT_NO_CHECKSUM,
        MAGIC,
        VERSION,
        seq & 0xFFFF,
        state["buttons"] & 0xFF,
        state["lx"],
        state["ly"],
        state["rx"],
        state["ry"],
        state["lt"],
        state["rt"],
    )
    return frame_without_checksum + bytes([checksum(frame_without_checksum[2:])])


def make_frame_payload(seq, state, shared, injected_driver1):
    buttons = state["buttons"]
    return {
        "seq": seq,
        "lx": state["lx"],
        "ly": state["ly"],
        "rx": state["rx"],
        "ry": state["ry"],
        "lt": state["lt"],
        "rt": state["rt"],
        "buttons": buttons,
        "cross": bool(buttons & BTN_CROSS),
        "circle": bool(buttons & BTN_CIRCLE),
        "square": bool(buttons & BTN_SQUARE),
        "triangle": bool(buttons & BTN_TRIANGLE),
        "left_bumper": bool(buttons & BTN_L1),
        "right_bumper": bool(buttons & BTN_R1),
        "injected_driver1": injected_driver1,
        "sent_count": shared.sent_count,
        "serial_ok": shared.serial_ok,
        "gamepad_ok": shared.gamepad_ok,
        "controller_name": shared.controller_name,
        "port": shared.port,
        "baud": shared.baud,
        "hz": shared.hz,
        "age_ms": 0,
    }


def run_transmitter(args, shared):
    pygame.init()
    pygame.joystick.init()

    if pygame.joystick.get_count() == 0:
        raise RuntimeError("No controller detected.")

    joystick = pygame.joystick.Joystick(0)
    joystick.init()

    ser = serial.Serial(args.port, args.baud, timeout=0)
    time.sleep(2.0)

    with shared.lock:
        shared.serial_ok = True
        shared.gamepad_ok = True
        shared.controller_name = joystick.get_name()
        shared.port = args.port
        shared.baud = args.baud
        shared.hz = args.hz

    shared.publish("notice", {"message": f"Using controller: {joystick.get_name()}"})
    shared.publish("notice", {"message": f"Serial open: {args.port} @ {args.baud}"})
    shared.publish("notice", {"message": f"Frame length: {FRAME_LEN} bytes"})

    seq = 0
    period = 1.0 / args.hz
    next_send = time.monotonic()

    try:
        while True:
            state = read_controller_state(joystick, args.l1_button, args.r1_button)
            injected_driver1 = shared.driver1_active()
            if injected_driver1:
                state["buttons"] |= BTN_START | BTN_A

            frame = build_frame(seq, state)
            ser.write(frame)

            with shared.lock:
                shared.sent_count += 1
                payload = make_frame_payload(seq, state, shared, injected_driver1)
                shared.latest = payload

            shared.publish("frame", payload)
            seq = (seq + 1) & 0xFFFF

            next_send += period
            sleep_for = next_send - time.monotonic()
            if sleep_for > 0:
                time.sleep(sleep_for)
            else:
                next_send = time.monotonic()

    finally:
        with shared.lock:
            shared.serial_ok = False
            shared.gamepad_ok = False
        ser.close()
        pygame.quit()


def make_handler(shared):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            return

        def do_GET(self):
            path = urlparse(self.path).path
            if path == "/":
                body = INDEX_HTML.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return

            if path == "/api/status":
                payload = shared.snapshot() or {"serial_ok": False, "gamepad_ok": False}
                body = json.dumps(payload).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return

            if path == "/events":
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "keep-alive")
                self.end_headers()

                q = shared.add_client()
                snap = shared.snapshot()
                try:
                    if snap:
                        self.wfile.write(f"event: frame\ndata: {json.dumps(snap)}\n\n".encode("utf-8"))
                        self.wfile.flush()
                    while True:
                        event, data = q.get(timeout=15)
                        self.wfile.write(f"event: {event}\ndata: {data}\n\n".encode("utf-8"))
                        self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, TimeoutError):
                    pass
                finally:
                    shared.remove_client(q)
                return

            self.send_error(404)

        def do_POST(self):
            path = urlparse(self.path).path
            if path == "/api/driver1":
                shared.pulse_driver1()
                body = b'{"ok":true}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return

            self.send_error(404)

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", required=True, help="Uno serial port, e.g. /dev/cu.usbmodemXXXX")
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument("--hz", type=float, default=FRAME_RATE_HZ)
    parser.add_argument("--web-host", default="127.0.0.1")
    parser.add_argument("--web-port", type=int, default=8765)
    parser.add_argument("--l1-button", type=int, default=9, help="pygame button index for PS4 L1/LB")
    parser.add_argument("--r1-button", type=int, default=10, help="pygame button index for PS4 R1/RB")
    args = parser.parse_args()

    shared = SharedState()
    server = ThreadingHTTPServer((args.web_host, args.web_port), make_handler(shared))
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    print(f"Dashboard: http://{args.web_host}:{args.web_port}")
    print("Press Ctrl-C to stop.")

    try:
        run_transmitter(args, shared)
    except KeyboardInterrupt:
        print("\nStopped.")
    finally:
        server.shutdown()


if __name__ == "__main__":
    main()
