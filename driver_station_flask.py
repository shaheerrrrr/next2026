import argparse
import json
import queue
import struct
import threading
import time
from collections import deque

from flask import Flask, Response, jsonify, request, stream_with_context
import pygame
import serial


MAGIC = b"\xA5\x5A"
VERSION = 3
FRAME_RATE_HZ = 20
SERIAL_BAUD = 115200

ROBOT_FLASH = 1
ROBOT_FABLE = 2
ROBOT_SOL = 3
ROBOT_ALL = 255

ROBOT_ACCENTS = {
    "flash": "#5692cc",
    "fable": "#c15f3c",
    "sol": "#74aa9c",
}

ROBOTS = {
    "flash": {
        "id": ROBOT_FLASH,
        "name": "Flash",
        "role": "Tele-op drive, intake, dump",
        "accent": ROBOT_ACCENTS["flash"],
        "profile": "standard",
    },
    "fable": {
        "id": ROBOT_FABLE,
        "name": "Fable",
        "role": "Tele-op now, GPS autonomy later",
        "accent": ROBOT_ACCENTS["fable"],
        "profile": "standard",
    },
    "sol": {
        "id": ROBOT_SOL,
        "name": "Sol",
        "role": "Turret/shooter controls",
        "accent": ROBOT_ACCENTS["sol"],
        "profile": "sol",
    },
}

ROBOT_BY_ID = {robot["id"]: key for key, robot in ROBOTS.items()}

# Packet v3:
# magic[2], version u8, target_robot u8, seq u16, buttons u16,
# lx i16, ly i16, rx i16, ry i16, lt u16, rt u16, checksum u8
PACK_FMT_NO_CHECKSUM = "<2sBBHHhhhhHH"
FRAME_LEN = struct.calcsize(PACK_FMT_NO_CHECKSUM) + 1

BTN_CROSS = 1 << 0
BTN_CIRCLE = 1 << 1
BTN_SQUARE = 1 << 2
BTN_TRIANGLE = 1 << 3
BTN_OPTIONS = 1 << 4
BTN_L1 = 1 << 5
BTN_R1 = 1 << 6
BTN_DPAD_UP = 1 << 7
BTN_DPAD_DOWN = 1 << 8
BTN_DPAD_LEFT = 1 << 9
BTN_DPAD_RIGHT = 1 << 10

EVENT_BACKLOG = 500
LOG_BACKLOG = 240


INDEX_HTML = r"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>LoRa Fleet Driver Station</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #0d1114;
      --panel: #151a1f;
      --panel-2: #1b2229;
      --panel-3: #202a32;
      --border: #2d3943;
      --text: #eef5f8;
      --muted: #9baab4;
      --accent: __ROBOT_ACCENT_FLASH__;
      --accent-soft: rgba(82, 210, 115, 0.16);
      --warn: #ffcc66;
      --bad: #ff6b6b;
      --shadow: 0 22px 55px rgba(0, 0, 0, 0.34);
    }

    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      background:
        radial-gradient(circle at top left, rgba(90, 169, 255, 0.12), transparent 32vw),
        radial-gradient(circle at top right, rgba(255, 184, 77, 0.12), transparent 34vw),
        var(--bg);
      color: var(--text);
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }

    .app {
      width: min(1360px, calc(100vw - 32px));
      margin: 0 auto;
      padding: 24px 0;
    }

    header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 20px;
      margin-bottom: 18px;
    }

    h1 {
      margin: 0;
      font-size: 30px;
      letter-spacing: 0;
    }

    .subtitle {
      margin-top: 6px;
      color: var(--muted);
      font-size: 14px;
    }

    .status-row {
      display: flex;
      justify-content: flex-end;
      flex-wrap: wrap;
      gap: 8px;
    }

    .pill {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      border: 1px solid var(--border);
      background: rgba(21, 26, 31, 0.84);
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
      box-shadow: 0 0 0 3px rgba(255, 107, 107, 0.13);
    }

    .ok .dot {
      background: var(--accent);
      box-shadow: 0 0 0 3px var(--accent-soft);
    }

    .warn .dot {
      background: var(--warn);
      box-shadow: 0 0 0 3px rgba(255, 204, 102, 0.13);
    }

    .robot-switch {
      position: relative;
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 4px;
      border: 1px solid var(--border);
      background: rgba(16, 22, 27, 0.92);
      border-radius: 14px;
      padding: 5px;
      margin-bottom: 16px;
      box-shadow: var(--shadow);
    }

    .switch-glider {
      position: absolute;
      top: 5px;
      bottom: 5px;
      left: 5px;
      width: calc((100% - 10px) / 3);
      border-radius: 10px;
      background: var(--accent);
      transition: transform 220ms ease, background 220ms ease, box-shadow 220ms ease;
      box-shadow: 0 0 28px var(--accent-soft);
    }

    .robot-tab {
      position: relative;
      z-index: 1;
      appearance: none;
      border: 0;
      background: transparent;
      color: var(--muted);
      border-radius: 10px;
      padding: 14px 12px;
      cursor: pointer;
      text-align: left;
      transition: color 180ms ease;
    }

    .robot-tab.active {
      color: #06100a;
    }

    .tab-name {
      display: block;
      font-size: 18px;
      font-weight: 760;
    }

    .tab-role {
      display: block;
      margin-top: 3px;
      font-size: 12px;
      font-weight: 650;
      opacity: 0.78;
    }

    main {
      display: grid;
      grid-template-columns: minmax(380px, 0.88fr) minmax(460px, 1.12fr);
      gap: 16px;
    }

    section {
      min-width: 0;
      border: 1px solid var(--border);
      background: rgba(21, 26, 31, 0.94);
      border-radius: 10px;
      box-shadow: var(--shadow);
      overflow: hidden;
    }

    .section-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      padding: 14px 16px;
      border-bottom: 1px solid var(--border);
      background: rgba(27, 34, 41, 0.74);
    }

    h2 {
      margin: 0;
      font-size: 15px;
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
      font-weight: 760;
      white-space: nowrap;
    }

    .controls-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
    }

    .stick-wrap, .control-block {
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
      overflow: hidden;
      background:
        linear-gradient(to right, transparent calc(50% - 1px), #3b4852 50%, transparent calc(50% + 1px)),
        linear-gradient(to bottom, transparent calc(50% - 1px), #3b4852 50%, transparent calc(50% + 1px)),
        #10161b;
    }

    .knob {
      position: absolute;
      left: 50%;
      top: 50%;
      width: 22px;
      height: 22px;
      border-radius: 999px;
      background: var(--accent);
      border: 2px solid rgba(255, 255, 255, 0.86);
      transform: translate(-50%, -50%);
      box-shadow: 0 0 22px var(--accent-soft);
    }

    .readout {
      display: flex;
      justify-content: space-between;
      gap: 8px;
      color: var(--muted);
      font-size: 12px;
      font-variant-numeric: tabular-nums;
      margin-top: 10px;
    }

    .bars {
      display: grid;
      gap: 12px;
      margin-top: 16px;
    }

    .bar-row {
      display: grid;
      grid-template-columns: 98px 1fr 54px;
      align-items: center;
      gap: 10px;
      color: var(--muted);
      font-size: 13px;
    }

    .bar {
      height: 10px;
      border-radius: 999px;
      overflow: hidden;
      background: #10161b;
      border: 1px solid var(--border);
    }

    .fill {
      height: 100%;
      width: 0%;
      background: var(--accent);
      transition: width 80ms linear;
    }

    .button-grid {
      display: grid;
      grid-template-columns: repeat(6, minmax(0, 1fr));
      gap: 10px;
      margin-top: 16px;
    }

    .btn-state {
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 12px 8px;
      min-height: 44px;
      display: flex;
      align-items: center;
      justify-content: center;
      text-align: center;
      color: var(--muted);
      background: var(--panel-2);
      font-size: 13px;
      font-weight: 720;
    }

    .btn-state.active {
      color: #06100a;
      border-color: var(--accent);
      background: var(--accent);
    }

    .dpad {
      display: grid;
      grid-template-columns: repeat(3, 46px);
      grid-template-rows: repeat(3, 46px);
      justify-content: center;
      gap: 8px;
      margin-top: 8px;
    }

    .dpad .btn-state {
      min-height: 46px;
      padding: 0;
      font-size: 18px;
    }

    .dpad .up { grid-column: 2; grid-row: 1; }
    .dpad .left { grid-column: 1; grid-row: 2; }
    .dpad .right { grid-column: 3; grid-row: 2; }
    .dpad .down { grid-column: 2; grid-row: 3; }

    .actions {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
    }

    button.action {
      appearance: none;
      border: 1px solid var(--border);
      background: var(--panel-3);
      color: var(--text);
      border-radius: 8px;
      padding: 9px 12px;
      font-weight: 760;
      cursor: pointer;
    }

    button.action.primary {
      background: var(--accent);
      color: #06100a;
      border-color: var(--accent);
    }

    .terminal {
      height: 620px;
      overflow: auto;
      background: #080b0e;
      border-radius: 8px;
      padding: 12px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
      font-size: 12px;
      line-height: 1.45;
      color: #d8e4e9;
      white-space: pre;
    }

    .terminal .muted { color: #82919a; }
    .terminal .pulse { color: #a6ffc8; }

    .hidden { display: none; }

    @media (max-width: 980px) {
      header { flex-direction: column; }
      .status-row { justify-content: flex-start; }
      main { grid-template-columns: 1fr; }
    }

    @media (max-width: 680px) {
      .app { width: min(100vw - 20px, 1360px); padding: 14px 0; }
      .robot-switch { grid-template-columns: 1fr; }
      .switch-glider { display: none; }
      .robot-tab.active { background: var(--accent); }
      .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .controls-grid { grid-template-columns: 1fr; }
      .button-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .terminal { height: 440px; }
    }
  </style>
</head>
<body>
  <div class="app">
    <header>
      <div>
        <h1>LoRa Fleet Driver Station</h1>
        <div class="subtitle" id="subtitle">Waiting for controller frames...</div>
      </div>
      <div class="status-row">
        <div class="pill" id="gamepadPill"><span class="dot"></span><span>Gamepad</span></div>
        <div class="pill" id="serialPill"><span class="dot"></span><span>Serial</span></div>
        <div class="pill" id="streamPill"><span class="dot"></span><span>Dashboard</span></div>
      </div>
    </header>

    <div class="robot-switch" id="robotSwitch">
      <div class="switch-glider" id="switchGlider"></div>
      <button class="robot-tab active" data-robot="flash"><span class="tab-name">Flash</span><span class="tab-role">tele-op</span></button>
      <button class="robot-tab" data-robot="fable"><span class="tab-name">Fable</span><span class="tab-role">tele-op + future auto</span></button>
      <button class="robot-tab" data-robot="sol"><span class="tab-name">Sol</span><span class="tab-role">turret / shooter</span></button>
    </div>

    <main>
      <section>
        <div class="section-head">
          <h2 id="liveTitle">Flash Live Control</h2>
          <div class="actions">
            <button class="action primary" id="driver1Btn">Register Driver 1</button>
          </div>
        </div>
        <div class="content">
          <div class="metrics">
            <div class="metric"><div class="label">Robot</div><div class="value" id="robotName">Flash</div></div>
            <div class="metric"><div class="label">Sequence</div><div class="value" id="seq">-</div></div>
            <div class="metric"><div class="label">Rate</div><div class="value" id="rate">-</div></div>
            <div class="metric"><div class="label">Sent</div><div class="value" id="sent">-</div></div>
          </div>

          <div class="controls-grid">
            <div class="stick-wrap">
              <div class="label">Left Stick</div>
              <div class="stick"><div class="knob" id="leftKnob"></div></div>
              <div class="readout"><span id="lx">x 0</span><span id="ly">y 0</span></div>
            </div>
            <div class="stick-wrap">
              <div class="label">Right Stick</div>
              <div class="stick"><div class="knob" id="rightKnob"></div></div>
              <div class="readout"><span id="rx">x 0</span><span id="ry">y 0</span></div>
            </div>
          </div>

          <div class="bars">
            <div class="bar-row" id="ltRow"><span>Left Trigger</span><div class="bar"><div class="fill" id="ltFill"></div></div><span id="lt">0</span></div>
            <div class="bar-row"><span>Right Trigger</span><div class="bar"><div class="fill" id="rtFill"></div></div><span id="rt">0</span></div>
          </div>

          <div class="button-grid" id="standardButtons">
            <div class="btn-state" id="cross">Cross / A</div>
            <div class="btn-state" id="circle">Circle / B</div>
            <div class="btn-state" id="square">Square / X</div>
            <div class="btn-state" id="triangle">Triangle / Y</div>
            <div class="btn-state" id="leftBumper">L1 / LB</div>
            <div class="btn-state" id="rightBumper">R1 / RB</div>
          </div>

          <div id="solControls" class="hidden">
            <div class="button-grid">
              <div class="btn-state" id="solX">X</div>
              <div class="btn-state" id="solRt">Right Trigger</div>
            </div>
            <div class="control-block" style="margin-top: 16px;">
              <div class="label">D-Pad</div>
              <div class="dpad">
                <div class="btn-state up" id="dpadUp">Up</div>
                <div class="btn-state left" id="dpadLeft">Left</div>
                <div class="btn-state right" id="dpadRight">Right</div>
                <div class="btn-state down" id="dpadDown">Down</div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section>
        <div class="section-head">
          <h2 id="logTitle">Flash Transmit Log</h2>
          <div class="actions">
            <button class="action" id="clearBtn">Clear</button>
            <button class="action" id="pauseBtn">Pause</button>
          </div>
        </div>
        <div class="content">
          <div class="terminal" id="terminal"></div>
        </div>
      </section>
    </main>
  </div>

  <script>
    const ROBOTS = {
      flash: { id: 1, name: 'Flash', accent: '__ROBOT_ACCENT_FLASH__', profile: 'standard' },
      fable: { id: 2, name: 'Fable', accent: '__ROBOT_ACCENT_FABLE__', profile: 'standard' },
      sol: { id: 3, name: 'Sol', accent: '__ROBOT_ACCENT_SOL__', profile: 'sol' }
    };
    const order = ['flash', 'fable', 'sol'];
    const logs = { flash: [], fable: [], sol: [] };
    const latestByRobot = {};
    const el = id => document.getElementById(id);
    let activeRobot = 'flash';
    let paused = false;
    let lastEventAt = 0;

    function setAccent(robotKey) {
      document.documentElement.style.setProperty('--accent', ROBOTS[robotKey].accent);
      document.documentElement.style.setProperty('--accent-soft', `${ROBOTS[robotKey].accent}29`);
    }

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

    function setActiveRobot(robotKey, fromServer = false) {
      activeRobot = robotKey;
      setAccent(robotKey);

      document.querySelectorAll('.robot-tab').forEach(tab => {
        tab.classList.toggle('active', tab.dataset.robot === robotKey);
      });

      const index = order.indexOf(robotKey);
      el('switchGlider').style.transform = `translateX(${index * 100}%)`;
      el('robotName').textContent = ROBOTS[robotKey].name;
      el('liveTitle').textContent = `${ROBOTS[robotKey].name} Live Control`;
      el('logTitle').textContent = `${ROBOTS[robotKey].name} Transmit Log`;

      const sol = ROBOTS[robotKey].profile === 'sol';
      el('standardButtons').classList.toggle('hidden', sol);
      el('solControls').classList.toggle('hidden', !sol);
      el('ltRow').classList.toggle('hidden', sol);

      renderLog();
      renderLatest(latestByRobot[robotKey]);

      if (!fromServer) {
        fetch(`/api/robot/${robotKey}`, { method: 'POST' }).catch(() => {});
      }
    }

    function frameLine(frame) {
      return (
        `seq=${String(frame.seq).padStart(5, ' ')} target=${frame.robot_name.padEnd(5, ' ')} ` +
        `lx=${String(frame.lx).padStart(5, ' ')} ly=${String(frame.ly).padStart(5, ' ')} ` +
        `rx=${String(frame.rx).padStart(5, ' ')} ry=${String(frame.ry).padStart(5, ' ')} ` +
        `lt=${String(frame.lt).padStart(4, ' ')} rt=${String(frame.rt).padStart(4, ' ')} ` +
        `a=${frame.cross ? 1 : 0} b=${frame.circle ? 1 : 0} x=${frame.square ? 1 : 0} y=${frame.triangle ? 1 : 0} ` +
        `l1=${frame.left_bumper ? 1 : 0} r1=${frame.right_bumper ? 1 : 0} ` +
        `dpad=${frame.dpad_up ? 'U' : '-'}${frame.dpad_down ? 'D' : '-'}${frame.dpad_left ? 'L' : '-'}${frame.dpad_right ? 'R' : '-'}`
      );
    }

    function renderLog() {
      const term = el('terminal');
      term.textContent = '';
      for (const item of logs[activeRobot]) {
        const line = document.createElement('div');
        if (item.injected_driver1) line.className = 'pulse';
        line.textContent = frameLine(item);
        term.appendChild(line);
      }
      term.scrollTop = term.scrollHeight;
    }

    function appendFrame(frame) {
      const key = frame.robot_key;
      logs[key].push(frame);
      while (logs[key].length > 1200) logs[key].shift();
      latestByRobot[key] = frame;
      if (key === activeRobot && !paused) {
        const line = document.createElement('div');
        if (frame.injected_driver1) line.className = 'pulse';
        line.textContent = frameLine(frame);
        el('terminal').appendChild(line);
        while (el('terminal').childNodes.length > 1200) el('terminal').removeChild(el('terminal').firstChild);
        el('terminal').scrollTop = el('terminal').scrollHeight;
      }
    }

    function renderLatest(frame) {
      if (!frame) return;
      el('seq').textContent = frame.seq;
      el('rate').textContent = `${frame.hz.toFixed(1)}`;
      el('sent').textContent = frame.sent_count;
      el('subtitle').textContent = `${frame.controller_name || 'Controller'} -> ${frame.port} @ ${frame.baud} | v${frame.version}, ${frame.frame_len} bytes`;

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
      setButton('solX', frame.square);
      setButton('solRt', frame.rt > 20);
      setButton('dpadUp', frame.dpad_up);
      setButton('dpadDown', frame.dpad_down);
      setButton('dpadLeft', frame.dpad_left);
      setButton('dpadRight', frame.dpad_right);
    }

    function applyStatus(status) {
      if (status.active_robot && status.active_robot !== activeRobot) {
        setActiveRobot(status.active_robot, true);
      }
      setPill('gamepadPill', status.gamepad_ok ? 'ok' : 'bad', status.gamepad_ok ? 'Gamepad OK' : 'Gamepad Down');
      setPill('serialPill', status.serial_ok ? 'ok' : 'bad', status.serial_ok ? 'Serial OK' : 'Serial Down');
      if (status.error) {
        const line = document.createElement('div');
        line.className = 'muted';
        line.textContent = status.error;
        el('terminal').appendChild(line);
      }
    }

    function connectEvents() {
      const source = new EventSource('/events');
      source.onopen = () => setPill('streamPill', 'ok', 'Dashboard Live');
      source.onerror = () => setPill('streamPill', 'warn', 'Reconnecting');
      source.addEventListener('status', e => {
        lastEventAt = Date.now();
        applyStatus(JSON.parse(e.data));
      });
      source.addEventListener('frame', e => {
        lastEventAt = Date.now();
        const frame = JSON.parse(e.data);
        appendFrame(frame);
        if (frame.robot_key === activeRobot) renderLatest(frame);
        setPill('streamPill', 'ok', 'Dashboard Live');
      });
      source.addEventListener('notice', e => {
        const line = document.createElement('div');
        line.className = 'muted';
        line.textContent = JSON.parse(e.data).message;
        el('terminal').appendChild(line);
      });
    }

    document.querySelectorAll('.robot-tab').forEach(tab => {
      tab.addEventListener('click', () => setActiveRobot(tab.dataset.robot));
    });

    window.addEventListener('keydown', event => {
      if (event.code !== 'Space' || event.repeat) return;
      const tag = event.target && event.target.tagName ? event.target.tagName.toLowerCase() : '';
      if (tag === 'input' || tag === 'textarea' || tag === 'select') return;

      event.preventDefault();
      const index = order.indexOf(activeRobot);
      const nextRobot = order[(index + 1) % order.length];
      setActiveRobot(nextRobot);
    });

    el('driver1Btn').addEventListener('click', async () => {
      el('driver1Btn').disabled = true;
      try {
        await fetch('/api/driver1', { method: 'POST' });
      } finally {
        setTimeout(() => { el('driver1Btn').disabled = false; }, 900);
      }
    });

    el('clearBtn').addEventListener('click', () => {
      logs[activeRobot] = [];
      renderLog();
    });
    el('pauseBtn').addEventListener('click', () => {
      paused = !paused;
      el('pauseBtn').textContent = paused ? 'Resume' : 'Pause';
    });

    setInterval(() => {
      if (!lastEventAt) return;
      if (Date.now() - lastEventAt > 900) setPill('streamPill', 'warn', 'No Recent Frames');
    }, 250);

    setActiveRobot('flash', true);
    connectEvents();
  </script>
</body>
</html>
"""


def apply_robot_accents(html):
    replacements = {
        "__ROBOT_ACCENT_FLASH__": ROBOT_ACCENTS["flash"],
        "__ROBOT_ACCENT_FABLE__": ROBOT_ACCENTS["fable"],
        "__ROBOT_ACCENT_SOL__": ROBOT_ACCENTS["sol"],
    }

    for token, value in replacements.items():
        html = html.replace(token, value)
    return html


INDEX_HTML = apply_robot_accents(INDEX_HTML)


class SharedState:
    def __init__(self):
        self.lock = threading.Lock()
        self.clients = set()
        self.active_robot = "flash"
        self.sent_count = 0
        self.serial_ok = False
        self.gamepad_ok = False
        self.controller_name = ""
        self.port = ""
        self.baud = 0
        self.hz = 0.0
        self.error = ""
        self.driver1_until = 0.0
        self.latest_by_robot = {}
        self.logs = {key: deque(maxlen=LOG_BACKLOG) for key in ROBOTS}

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

    def snapshot(self):
        with self.lock:
            return {
                "active_robot": self.active_robot,
                "serial_ok": self.serial_ok,
                "gamepad_ok": self.gamepad_ok,
                "controller_name": self.controller_name,
                "port": self.port,
                "baud": self.baud,
                "hz": self.hz,
                "sent_count": self.sent_count,
                "error": self.error,
                "version": VERSION,
                "frame_len": FRAME_LEN,
            }

    def set_status(self, **kwargs):
        with self.lock:
            for key, value in kwargs.items():
                setattr(self, key, value)
            snap = self.snapshot_unlocked()
        self.publish("status", snap)

    def snapshot_unlocked(self):
        return {
            "active_robot": self.active_robot,
            "serial_ok": self.serial_ok,
            "gamepad_ok": self.gamepad_ok,
            "controller_name": self.controller_name,
            "port": self.port,
            "baud": self.baud,
            "hz": self.hz,
            "sent_count": self.sent_count,
            "error": self.error,
            "version": VERSION,
            "frame_len": FRAME_LEN,
        }

    def set_active_robot(self, robot_key):
        with self.lock:
            if robot_key not in ROBOTS:
                return False
            self.active_robot = robot_key
            snap = self.snapshot_unlocked()
        self.publish("status", snap)
        return True

    def pulse_driver1(self, seconds=0.7):
        with self.lock:
            self.driver1_until = max(self.driver1_until, time.monotonic() + seconds)

    def driver1_active(self):
        with self.lock:
            return time.monotonic() < self.driver1_until

    def record_frame(self, robot_key, payload):
        with self.lock:
            self.sent_count += 1
            payload["sent_count"] = self.sent_count
            self.latest_by_robot[robot_key] = payload
            self.logs[robot_key].append(payload)
        self.publish("frame", payload)


def apply_deadband(value, deadband=0.06):
    if abs(value) < deadband:
        return 0.0
    return value


def clamp(value, low, high):
    return max(low, min(high, value))


def safe_axis(joystick, index, default=0.0):
    if index < joystick.get_numaxes():
        return joystick.get_axis(index)
    return default


def safe_button(joystick, index):
    return index >= 0 and index < joystick.get_numbuttons() and joystick.get_button(index)


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


def read_controller_state(joystick, args):
    pygame.event.pump()

    lx = stick_to_i16(apply_deadband(safe_axis(joystick, 0)))
    ly = stick_to_i16(apply_deadband(safe_axis(joystick, 1)))
    rx = stick_to_i16(apply_deadband(safe_axis(joystick, 2)))
    ry = stick_to_i16(apply_deadband(safe_axis(joystick, 3)))
    lt = trigger_to_u16(safe_axis(joystick, 4, -1.0))
    rt = trigger_to_u16(safe_axis(joystick, 5, -1.0))

    buttons = 0
    if safe_button(joystick, 0):
        buttons |= BTN_CROSS
    if safe_button(joystick, 1):
        buttons |= BTN_CIRCLE
    if safe_button(joystick, 2):
        buttons |= BTN_SQUARE
    if safe_button(joystick, 3):
        buttons |= BTN_TRIANGLE
    if safe_button(joystick, args.l1_button):
        buttons |= BTN_L1
    if safe_button(joystick, args.r1_button):
        buttons |= BTN_R1

    if joystick.get_numhats() > 0:
        hat_x, hat_y = joystick.get_hat(0)
        if hat_y > 0:
            buttons |= BTN_DPAD_UP
        if hat_y < 0:
            buttons |= BTN_DPAD_DOWN
        if hat_x < 0:
            buttons |= BTN_DPAD_LEFT
        if hat_x > 0:
            buttons |= BTN_DPAD_RIGHT

    if safe_button(joystick, args.dpad_up_button):
        buttons |= BTN_DPAD_UP
    if safe_button(joystick, args.dpad_down_button):
        buttons |= BTN_DPAD_DOWN
    if safe_button(joystick, args.dpad_left_button):
        buttons |= BTN_DPAD_LEFT
    if safe_button(joystick, args.dpad_right_button):
        buttons |= BTN_DPAD_RIGHT

    return {
        "lx": lx,
        "ly": ly,
        "rx": rx,
        "ry": ry,
        "lt": lt,
        "rt": rt,
        "buttons": buttons,
    }


def build_frame(seq, target_robot_id, state):
    frame_without_checksum = struct.pack(
        PACK_FMT_NO_CHECKSUM,
        MAGIC,
        VERSION,
        target_robot_id & 0xFF,
        seq & 0xFFFF,
        state["buttons"] & 0xFFFF,
        state["lx"],
        state["ly"],
        state["rx"],
        state["ry"],
        state["lt"],
        state["rt"],
    )
    return frame_without_checksum + bytes([checksum(frame_without_checksum[2:])])


def make_frame_payload(seq, robot_key, state, shared, injected_driver1):
    buttons = state["buttons"]
    robot = ROBOTS[robot_key]
    return {
        "version": VERSION,
        "frame_len": FRAME_LEN,
        "seq": seq,
        "robot_key": robot_key,
        "robot_id": robot["id"],
        "robot_name": robot["name"],
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
        "dpad_up": bool(buttons & BTN_DPAD_UP),
        "dpad_down": bool(buttons & BTN_DPAD_DOWN),
        "dpad_left": bool(buttons & BTN_DPAD_LEFT),
        "dpad_right": bool(buttons & BTN_DPAD_RIGHT),
        "injected_driver1": injected_driver1,
        "sent_count": shared.sent_count,
        "serial_ok": shared.serial_ok,
        "gamepad_ok": shared.gamepad_ok,
        "controller_name": shared.controller_name,
        "port": shared.port,
        "baud": shared.baud,
        "hz": shared.hz,
    }


def run_transmitter(args, shared):
    pygame.init()
    pygame.joystick.init()

    ser = None
    try:
        if pygame.joystick.get_count() == 0:
            raise RuntimeError("No controller detected.")

        joystick = pygame.joystick.Joystick(0)
        joystick.init()
        ser = serial.Serial(args.port, args.baud, timeout=0)
        time.sleep(2.0)

        shared.set_status(
            serial_ok=True,
            gamepad_ok=True,
            controller_name=joystick.get_name(),
            port=args.port,
            baud=args.baud,
            hz=args.hz,
            error="",
        )
        shared.publish("notice", {"message": f"Using controller: {joystick.get_name()}"})
        shared.publish(
            "notice",
            {
                "message": (
                    f"Controller inputs: {joystick.get_numaxes()} axes, "
                    f"{joystick.get_numbuttons()} buttons, {joystick.get_numhats()} hats"
                )
            },
        )
        shared.publish(
            "notice",
            {
                "message": (
                    "D-pad mapping: hat 0 plus buttons "
                    f"U={args.dpad_up_button}, D={args.dpad_down_button}, "
                    f"L={args.dpad_left_button}, R={args.dpad_right_button}"
                )
            },
        )
        shared.publish("notice", {"message": f"Serial open: {args.port} @ {args.baud}"})
        shared.publish("notice", {"message": f"Protocol v{VERSION}, frame length: {FRAME_LEN} bytes"})

        seq = 0
        period = 1.0 / args.hz
        next_send = time.monotonic()

        while True:
            with shared.lock:
                robot_key = shared.active_robot
            robot_id = ROBOTS[robot_key]["id"]
            state = read_controller_state(joystick, args)

            injected_driver1 = shared.driver1_active()
            if injected_driver1:
                state["buttons"] |= BTN_OPTIONS | BTN_CROSS

            frame = build_frame(seq, robot_id, state)
            ser.write(frame)

            payload = make_frame_payload(seq, robot_key, state, shared, injected_driver1)
            shared.record_frame(robot_key, payload)
            seq = (seq + 1) & 0xFFFF

            next_send += period
            sleep_for = next_send - time.monotonic()
            if sleep_for > 0:
                time.sleep(sleep_for)
            else:
                next_send = time.monotonic()

    except Exception as exc:
        shared.set_status(serial_ok=False, gamepad_ok=False, error=str(exc))
        shared.publish("notice", {"message": f"Transmitter stopped: {exc}"})
    finally:
        if ser is not None:
            ser.close()
        pygame.quit()


def create_app(shared):
    app = Flask(__name__)

    @app.get("/")
    def index():
        return INDEX_HTML

    @app.get("/api/status")
    def status():
        return jsonify(shared.snapshot())

    @app.post("/api/robot/<robot_key>")
    def select_robot(robot_key):
        if not shared.set_active_robot(robot_key):
            return jsonify({"ok": False, "error": "unknown robot"}), 404
        return jsonify({"ok": True, "active_robot": robot_key})

    @app.post("/api/driver1")
    def driver1():
        shared.pulse_driver1()
        return jsonify({"ok": True})

    @app.get("/events")
    def events():
        q = shared.add_client()

        def generate():
            try:
                yield f"event: status\ndata: {json.dumps(shared.snapshot())}\n\n"
                while True:
                    try:
                        event, data = q.get(timeout=15)
                        yield f"event: {event}\ndata: {data}\n\n"
                    except queue.Empty:
                        yield "event: ping\ndata: {}\n\n"
            finally:
                shared.remove_client(q)

        return Response(stream_with_context(generate()), mimetype="text/event-stream")

    return app


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", required=True, help="Uno serial port, e.g. /dev/cu.usbmodemXXXX")
    parser.add_argument("--baud", type=int, default=SERIAL_BAUD)
    parser.add_argument("--hz", type=float, default=FRAME_RATE_HZ)
    parser.add_argument("--web-host", default="127.0.0.1")
    parser.add_argument("--web-port", type=int, default=8765)
    parser.add_argument("--l1-button", type=int, default=9, help="pygame button index for PS4 L1/LB")
    parser.add_argument("--r1-button", type=int, default=10, help="pygame button index for PS4 R1/RB")
    parser.add_argument("--dpad-up-button", type=int, default=11, help="fallback pygame button index for D-pad up")
    parser.add_argument("--dpad-down-button", type=int, default=12, help="fallback pygame button index for D-pad down")
    parser.add_argument("--dpad-left-button", type=int, default=13, help="fallback pygame button index for D-pad left")
    parser.add_argument("--dpad-right-button", type=int, default=14, help="fallback pygame button index for D-pad right")
    args = parser.parse_args()

    shared = SharedState()
    app = create_app(shared)
    server_thread = threading.Thread(
        target=lambda: app.run(host=args.web_host, port=args.web_port, threaded=True, use_reloader=False),
        daemon=True,
    )
    server_thread.start()

    print(f"Dashboard: http://{args.web_host}:{args.web_port}")
    print("Press Ctrl-C to stop.")
    try:
        run_transmitter(args, shared)
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
