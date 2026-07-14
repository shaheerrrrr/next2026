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

FABLE_NAV_SERIAL_BAUD = 115200
FABLE_NAV_DEFAULT_FIELD_METERS = 12.0

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

    .robot-tab.auto::after {
      content: "AUTO";
      position: absolute;
      right: 10px;
      top: 10px;
      padding: 3px 6px;
      border-radius: 999px;
      background: #ffcc66;
      color: #15100a;
      font-size: 10px;
      font-weight: 840;
      letter-spacing: 0.04em;
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

    .nav-panel {
      margin-top: 16px;
      border: 1px solid var(--border);
      border-radius: 10px;
      overflow: hidden;
      background: var(--panel-2);
    }

    .nav-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      padding: 12px;
      border-bottom: 1px solid var(--border);
    }

    .nav-mode {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      border: 1px solid var(--border);
      border-radius: 999px;
      padding: 6px 10px;
      color: var(--muted);
      font-size: 12px;
      font-weight: 760;
      white-space: nowrap;
    }

    .nav-mode.auto {
      color: #15100a;
      background: #ffcc66;
      border-color: #ffcc66;
    }

    .field-map {
      position: relative;
      height: 300px;
      margin: 12px;
      border: 1px solid #394650;
      border-radius: 8px;
      overflow: hidden;
      cursor: crosshair;
      background:
        linear-gradient(to right, rgba(255,255,255,0.055) 1px, transparent 1px),
        linear-gradient(to bottom, rgba(255,255,255,0.055) 1px, transparent 1px),
        radial-gradient(circle at center, rgba(193,95,60,0.12), transparent 48%),
        #10161b;
      background-size: 12.5% 12.5%, 12.5% 12.5%, auto, auto;
    }

    .field-map.calibrated {
      border-color: var(--accent);
      box-shadow: inset 0 0 0 1px rgba(255,255,255,0.08), 0 0 24px rgba(193,95,60,0.14);
    }

    .field-empty {
      position: absolute;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 24px;
      color: var(--muted);
      text-align: center;
      font-size: 13px;
      pointer-events: none;
    }

    .field-line {
      position: absolute;
      left: 0;
      top: 0;
      width: 100%;
      height: 100%;
      pointer-events: none;
    }

    .field-line rect {
      transition: opacity 160ms ease, stroke 160ms ease;
    }

    .field-dot {
      position: absolute;
      width: 16px;
      height: 16px;
      border-radius: 999px;
      transform: translate(-50%, -50%);
      border: 2px solid rgba(255,255,255,0.92);
      box-shadow: 0 0 18px rgba(0,0,0,0.5);
      display: none;
    }

    .field-dot.current {
      background: var(--accent);
    }

    .field-dot.target {
      background: #ffcc66;
    }

    .nav-details {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 10px;
      padding: 0 12px 12px;
    }

    .nav-detail {
      border: 1px solid var(--border);
      border-radius: 8px;
      background: #151b20;
      padding: 10px;
      min-width: 0;
    }

    .nav-detail .value {
      font-size: 14px;
      overflow-wrap: anywhere;
    }

    .nav-actions {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
      padding: 0 12px 12px;
    }

    .modal-backdrop {
      position: fixed;
      inset: 0;
      z-index: 20;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 20px;
      background: rgba(3, 6, 8, 0.72);
      backdrop-filter: blur(8px);
    }

    .modal-backdrop.hidden {
      display: none;
    }

    .modal {
      width: min(760px, 100%);
      max-height: calc(100vh - 40px);
      overflow: auto;
      border: 1px solid var(--border);
      border-radius: 10px;
      background: #151a1f;
      box-shadow: 0 24px 70px rgba(0,0,0,0.48);
    }

    .modal-head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 16px;
      padding: 16px;
      border-bottom: 1px solid var(--border);
    }

    .modal-body {
      padding: 16px;
    }

    .corner-grid {
      display: grid;
      gap: 12px;
    }

    .corner-row {
      display: grid;
      grid-template-columns: 112px minmax(0, 1fr) minmax(0, 1fr) auto;
      gap: 10px;
      align-items: end;
      padding: 12px;
      border: 1px solid var(--border);
      border-radius: 8px;
      background: var(--panel-2);
    }

    .corner-name {
      color: var(--text);
      font-weight: 780;
      padding-bottom: 9px;
    }

    .field-input label {
      display: block;
      color: var(--muted);
      font-size: 12px;
      margin-bottom: 5px;
    }

    .field-input input {
      width: 100%;
      border: 1px solid var(--border);
      border-radius: 8px;
      background: #10161b;
      color: var(--text);
      padding: 9px 10px;
      font: inherit;
      font-variant-numeric: tabular-nums;
    }

    .modal-actions {
      display: flex;
      justify-content: space-between;
      gap: 10px;
      flex-wrap: wrap;
      padding: 16px;
      border-top: 1px solid var(--border);
    }

    .modal-actions .right {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
    }

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
        <div class="pill" id="fableNavPill"><span class="dot"></span><span>Fable Nav</span></div>
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

          <div id="fableNavPanel" class="nav-panel hidden">
            <div class="nav-head">
              <div>
                <h2>Fable Navigation</h2>
                <div class="label" id="fableNavSubtitle">Waiting for GPS telemetry...</div>
              </div>
              <div class="actions">
                <button class="action" id="fableCalibrateBtn">Calibrate Field</button>
                <div class="nav-mode" id="fableNavMode">TELE-OP</div>
              </div>
            </div>
            <div class="field-map" id="fableField">
              <svg class="field-line" viewBox="0 0 100 100" preserveAspectRatio="none">
                <rect id="fableFieldOutline" x="5" y="5" width="90" height="90" fill="none" stroke="#c15f3c" stroke-width="0.8" stroke-dasharray="2 2" opacity="0.28"/>
                <line id="fableTargetLine" x1="50" y1="50" x2="50" y2="50" stroke="#ffcc66" stroke-width="0.8" stroke-dasharray="2 2" opacity="0"/>
              </svg>
              <div class="field-empty" id="fableFieldEmpty">GPS fix required for fallback mode. Calibrate field corners to use the rectangle without a current fix.</div>
              <div class="field-dot current" id="fableCurrentDot" title="Current position"></div>
              <div class="field-dot target" id="fableTargetDot" title="Selected target"></div>
            </div>
            <div class="nav-details">
              <div class="nav-detail"><div class="label">Current Position</div><div class="value" id="fableCurrentCoord">--</div></div>
              <div class="nav-detail"><div class="label">Selected Target</div><div class="value" id="fableTargetCoord">Click the field</div></div>
              <div class="nav-detail"><div class="label">GPS Quality</div><div class="value" id="fableGpsQuality">--</div></div>
              <div class="nav-detail"><div class="label">ESP-NOW Link</div><div class="value" id="fableLinkState">--</div></div>
            </div>
            <div class="nav-actions">
              <button class="action primary" id="fableSendTargetBtn" disabled>Send Target</button>
              <button class="action" id="fableCancelAutoBtn">Cancel Auto</button>
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

  <div class="modal-backdrop hidden" id="fableCalibrationModal">
    <div class="modal">
      <div class="modal-head">
        <div>
          <h2>Calibrate Fable Field</h2>
          <div class="label">Enter GPS coordinates for each corner. The map will use these as the field rectangle.</div>
        </div>
        <button class="action" id="fableCalCloseBtn">Close</button>
      </div>
      <div class="modal-body">
        <div class="corner-grid">
          <div class="corner-row" data-corner="nw">
            <div class="corner-name">Northwest</div>
            <div class="field-input"><label>Latitude</label><input id="calNwLat" inputmode="decimal" placeholder="37.4219999"></div>
            <div class="field-input"><label>Longitude</label><input id="calNwLon" inputmode="decimal" placeholder="-122.0840575"></div>
            <button class="action use-current" data-corner="nw">Use Current</button>
          </div>
          <div class="corner-row" data-corner="ne">
            <div class="corner-name">Northeast</div>
            <div class="field-input"><label>Latitude</label><input id="calNeLat" inputmode="decimal"></div>
            <div class="field-input"><label>Longitude</label><input id="calNeLon" inputmode="decimal"></div>
            <button class="action use-current" data-corner="ne">Use Current</button>
          </div>
          <div class="corner-row" data-corner="se">
            <div class="corner-name">Southeast</div>
            <div class="field-input"><label>Latitude</label><input id="calSeLat" inputmode="decimal"></div>
            <div class="field-input"><label>Longitude</label><input id="calSeLon" inputmode="decimal"></div>
            <button class="action use-current" data-corner="se">Use Current</button>
          </div>
          <div class="corner-row" data-corner="sw">
            <div class="corner-name">Southwest</div>
            <div class="field-input"><label>Latitude</label><input id="calSwLat" inputmode="decimal"></div>
            <div class="field-input"><label>Longitude</label><input id="calSwLon" inputmode="decimal"></div>
            <button class="action use-current" data-corner="sw">Use Current</button>
          </div>
        </div>
      </div>
      <div class="modal-actions">
        <button class="action" id="fableCalClearBtn">Clear Calibration</button>
        <div class="right">
          <button class="action" id="fableCalCancelBtn">Cancel</button>
          <button class="action primary" id="fableCalSaveBtn">Save Field</button>
        </div>
      </div>
    </div>
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
    const FABLE_FIELD_METERS = __FABLE_FIELD_METERS__;
    let activeRobot = 'flash';
    let paused = false;
    let lastEventAt = 0;
    let fableNav = {};
    let fableFieldCenter = null;
    let fableSelectedTarget = null;
    let fableFieldCalibration = null;

    function loadFableFieldCalibration() {
      try {
        const raw = localStorage.getItem('fableFieldCalibration');
        fableFieldCalibration = raw ? JSON.parse(raw) : null;
      } catch {
        fableFieldCalibration = null;
      }
    }

    function saveFableFieldCalibration(calibration) {
      fableFieldCalibration = calibration;
      if (calibration) {
        localStorage.setItem('fableFieldCalibration', JSON.stringify(calibration));
      } else {
        localStorage.removeItem('fableFieldCalibration');
      }
      updateFableNav(fableNav);
    }

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
      const fable = robotKey === 'fable';
      el('standardButtons').classList.toggle('hidden', sol);
      el('solControls').classList.toggle('hidden', !sol);
      el('fableNavPanel').classList.toggle('hidden', !fable);
      el('ltRow').classList.toggle('hidden', sol);

      renderLog();
      renderLatest(latestByRobot[robotKey]);

      if (!fromServer) {
        fetch(`/api/robot/${robotKey}`, { method: 'POST' }).catch(() => {});
      }
    }

    function formatCoord(lat, lon) {
      if (lat === null || lon === null || lat === undefined || lon === undefined) return '--';
      return `${Number(lat).toFixed(7)}, ${Number(lon).toFixed(7)}`;
    }

    function fableLatLonToPoint(lat, lon) {
      if (fableFieldCalibration) {
        return calibratedLatLonToPoint(lat, lon);
      }
      if (!fableFieldCenter || lat === null || lon === null || lat === undefined || lon === undefined) return null;
      const metersPerDegLat = 111320;
      const metersPerDegLon = 111320 * Math.cos(fableFieldCenter.lat * Math.PI / 180);
      const dx = (lon - fableFieldCenter.lon) * metersPerDegLon;
      const dy = (lat - fableFieldCenter.lat) * metersPerDegLat;
      return {
        x: 50 + (dx / FABLE_FIELD_METERS) * 100,
        y: 50 - (dy / FABLE_FIELD_METERS) * 100
      };
    }

    function fablePointToLatLon(xPercent, yPercent) {
      if (fableFieldCalibration) {
        return calibratedPointToLatLon(xPercent, yPercent);
      }
      if (!fableFieldCenter) return null;
      const metersPerDegLat = 111320;
      const metersPerDegLon = 111320 * Math.cos(fableFieldCenter.lat * Math.PI / 180);
      const dx = ((xPercent - 50) / 100) * FABLE_FIELD_METERS;
      const dy = ((50 - yPercent) / 100) * FABLE_FIELD_METERS;
      return {
        lat: fableFieldCenter.lat + dy / metersPerDegLat,
        lon: fableFieldCenter.lon + dx / metersPerDegLon
      };
    }

    function metersPerDegLonAt(lat) {
      return 111320 * Math.cos(lat * Math.PI / 180);
    }

    function latLonToMeters(point, originLat, originLon) {
      return {
        x: (point.lon - originLon) * metersPerDegLonAt(originLat),
        y: (point.lat - originLat) * 111320
      };
    }

    function metersToLatLon(point, originLat, originLon) {
      return {
        lat: originLat + point.y / 111320,
        lon: originLon + point.x / metersPerDegLonAt(originLat)
      };
    }

    function calibratedBasis() {
      if (!fableFieldCalibration) return null;
      const nw = fableFieldCalibration.nw;
      const ne = fableFieldCalibration.ne;
      const se = fableFieldCalibration.se;
      const sw = fableFieldCalibration.sw;
      return {
        origin: nw,
        p00: { x: 0, y: 0 },
        p10: latLonToMeters(ne, nw.lat, nw.lon),
        p11: latLonToMeters(se, nw.lat, nw.lon),
        p01: latLonToMeters(sw, nw.lat, nw.lon)
      };
    }

    function bilinearMeters(basis, a, b) {
      const w00 = (1 - a) * (1 - b);
      const w10 = a * (1 - b);
      const w11 = a * b;
      const w01 = (1 - a) * b;
      return {
        x: basis.p00.x * w00 + basis.p10.x * w10 + basis.p11.x * w11 + basis.p01.x * w01,
        y: basis.p00.y * w00 + basis.p10.y * w10 + basis.p11.y * w11 + basis.p01.y * w01
      };
    }

    function calibratedLatLonToPoint(lat, lon) {
      if (lat === null || lon === null || lat === undefined || lon === undefined) return null;
      const basis = calibratedBasis();
      if (!basis) return null;
      const p = latLonToMeters({ lat, lon }, basis.origin.lat, basis.origin.lon);
      const det = basis.p10.x * basis.p01.y - basis.p10.y * basis.p01.x;
      if (Math.abs(det) < 0.001) return null;
      let a = (p.x * basis.p01.y - p.y * basis.p01.x) / det;
      let b = (basis.p10.x * p.y - basis.p10.y * p.x) / det;

      for (let i = 0; i < 6; i++) {
        const q = bilinearMeters(basis, a, b);
        const fx = q.x - p.x;
        const fy = q.y - p.y;
        const da = {
          x: (1 - b) * (basis.p10.x - basis.p00.x) + b * (basis.p11.x - basis.p01.x),
          y: (1 - b) * (basis.p10.y - basis.p00.y) + b * (basis.p11.y - basis.p01.y)
        };
        const db = {
          x: (1 - a) * (basis.p01.x - basis.p00.x) + a * (basis.p11.x - basis.p10.x),
          y: (1 - a) * (basis.p01.y - basis.p00.y) + a * (basis.p11.y - basis.p10.y)
        };
        const jDet = da.x * db.y - da.y * db.x;
        if (Math.abs(jDet) < 0.001) break;
        const stepA = (fx * db.y - fy * db.x) / jDet;
        const stepB = (da.x * fy - da.y * fx) / jDet;
        a -= stepA;
        b -= stepB;
      }

      return { x: a * 100, y: b * 100 };
    }

    function calibratedPointToLatLon(xPercent, yPercent) {
      const basis = calibratedBasis();
      if (!basis) return null;
      const a = xPercent / 100;
      const b = yPercent / 100;
      const p = bilinearMeters(basis, a, b);
      return metersToLatLon(p, basis.origin.lat, basis.origin.lon);
    }

    function placeDot(id, point) {
      const dot = el(id);
      if (!point) {
        dot.style.display = 'none';
        return;
      }
      dot.style.display = 'block';
      dot.style.left = `${Math.max(0, Math.min(100, point.x))}%`;
      dot.style.top = `${Math.max(0, Math.min(100, point.y))}%`;
      dot.style.opacity = point.x < 0 || point.x > 100 || point.y < 0 || point.y > 100 ? 0.45 : 1;
    }

    function setTargetLine(currentPoint, targetPoint) {
      const line = el('fableTargetLine');
      if (!currentPoint || !targetPoint) {
        line.setAttribute('opacity', '0');
        return;
      }
      line.setAttribute('x1', Math.max(0, Math.min(100, currentPoint.x)));
      line.setAttribute('y1', Math.max(0, Math.min(100, currentPoint.y)));
      line.setAttribute('x2', Math.max(0, Math.min(100, targetPoint.x)));
      line.setAttribute('y2', Math.max(0, Math.min(100, targetPoint.y)));
      line.setAttribute('opacity', '1');
    }

    function updateFableNav(nav) {
      fableNav = Object.assign({}, fableNav, nav || {});
      const hasCurrent = Boolean(fableNav.gps_valid && fableNav.lat !== null && fableNav.lon !== null);

      if (hasCurrent && !fableFieldCenter) {
        fableFieldCenter = { lat: fableNav.lat, lon: fableNav.lon };
      }

      const auto = fableNav.mode === 'autonomous';
      document.querySelector('[data-robot="fable"]').classList.toggle('auto', auto);
      el('fableNavMode').textContent = auto ? 'AUTONOMOUS' : 'TELE-OP';
      el('fableNavMode').classList.toggle('auto', auto);
      setPill('fableNavPill', fableNav.serial_ok ? (auto ? 'warn' : 'ok') : 'bad', fableNav.serial_ok ? (auto ? 'Fable Auto' : 'Fable Nav OK') : 'Fable Nav Down');

      const fixLabel = fableNav.fix_quality === 2 ? 'good' : (fableNav.fix_quality === 1 ? 'weak' : 'none');
      const calibrated = Boolean(fableFieldCalibration);
      el('fableField').classList.toggle('calibrated', calibrated);
      el('fableFieldOutline').setAttribute('opacity', calibrated ? '1' : '0.28');
      el('fableFieldOutline').setAttribute('stroke-dasharray', calibrated ? '0' : '2 2');
      el('fableFieldOutline').setAttribute('stroke', calibrated ? ROBOTS.fable.accent : '#c15f3c');
      el('fableNavSubtitle').textContent = calibrated
        ? 'Calibrated field corners active'
        : (hasCurrent ? `Fallback field: ${FABLE_FIELD_METERS.toFixed(1)} m around first GPS fix` : (fableNav.error || 'Waiting for GPS telemetry...'));
      el('fableCurrentCoord').textContent = formatCoord(fableNav.lat, fableNav.lon);
      el('fableGpsQuality').textContent = `${fixLabel}, sats ${fableNav.satellites || 0}, HDOP ${fableNav.hdop ?? '--'}`;
      el('fableLinkState').textContent = fableNav.link_alive ? `driver heartbeat alive, age ${fableNav.command_age_ms ?? '--'} ms` : 'telemetry received, waiting for driver heartbeat';

      if (!fableSelectedTarget && fableNav.target_valid && fableNav.target_lat !== null && fableNav.target_lon !== null) {
        fableSelectedTarget = { lat: fableNav.target_lat, lon: fableNav.target_lon };
      }
      el('fableTargetCoord').textContent = fableSelectedTarget ? formatCoord(fableSelectedTarget.lat, fableSelectedTarget.lon) : 'Click the field';
      el('fableSendTargetBtn').disabled = !fableSelectedTarget || !fableNav.serial_ok;

      const currentPoint = hasCurrent ? fableLatLonToPoint(fableNav.lat, fableNav.lon) : null;
      const targetPoint = fableSelectedTarget ? fableLatLonToPoint(fableSelectedTarget.lat, fableSelectedTarget.lon) : null;
      placeDot('fableCurrentDot', currentPoint);
      placeDot('fableTargetDot', targetPoint);
      setTargetLine(currentPoint, targetPoint);
      el('fableFieldEmpty').style.display = (hasCurrent || calibrated) ? 'none' : 'flex';
    }

    function cornerInputIds(corner) {
      const prefix = { nw: 'Nw', ne: 'Ne', se: 'Se', sw: 'Sw' }[corner];
      return { lat: `cal${prefix}Lat`, lon: `cal${prefix}Lon` };
    }

    function setCornerInputs(corner, point) {
      const ids = cornerInputIds(corner);
      el(ids.lat).value = point && point.lat !== undefined ? Number(point.lat).toFixed(7) : '';
      el(ids.lon).value = point && point.lon !== undefined ? Number(point.lon).toFixed(7) : '';
    }

    function getCornerInputs(corner) {
      const ids = cornerInputIds(corner);
      const lat = Number(el(ids.lat).value);
      const lon = Number(el(ids.lon).value);
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
      return { lat, lon };
    }

    function openFableCalibrationModal() {
      for (const corner of ['nw', 'ne', 'se', 'sw']) {
        setCornerInputs(corner, fableFieldCalibration ? fableFieldCalibration[corner] : null);
      }
      el('fableCalibrationModal').classList.remove('hidden');
    }

    function closeFableCalibrationModal() {
      el('fableCalibrationModal').classList.add('hidden');
    }

    function saveFableCalibrationFromInputs() {
      const calibration = {};
      for (const corner of ['nw', 'ne', 'se', 'sw']) {
        const point = getCornerInputs(corner);
        if (!point) {
          alert('Please enter valid latitude and longitude for all four corners.');
          return;
        }
        calibration[corner] = point;
      }
      saveFableFieldCalibration(calibration);
      closeFableCalibrationModal();
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
      if (status.fable_nav) updateFableNav(status.fable_nav);
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
      source.addEventListener('fable_nav', e => {
        lastEventAt = Date.now();
        updateFableNav(JSON.parse(e.data));
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

    el('fableCalibrateBtn').addEventListener('click', openFableCalibrationModal);
    el('fableCalCloseBtn').addEventListener('click', closeFableCalibrationModal);
    el('fableCalCancelBtn').addEventListener('click', closeFableCalibrationModal);
    el('fableCalSaveBtn').addEventListener('click', saveFableCalibrationFromInputs);
    el('fableCalClearBtn').addEventListener('click', () => {
      saveFableFieldCalibration(null);
      for (const corner of ['nw', 'ne', 'se', 'sw']) {
        setCornerInputs(corner, null);
      }
      closeFableCalibrationModal();
    });
    document.querySelectorAll('.use-current').forEach(button => {
      button.addEventListener('click', event => {
        event.preventDefault();
        if (!fableNav.gps_valid || fableNav.lat === null || fableNav.lon === null) {
          alert('No current GPS fix is available yet.');
          return;
        }
        setCornerInputs(button.dataset.corner, { lat: fableNav.lat, lon: fableNav.lon });
      });
    });

    el('fableField').addEventListener('click', event => {
      if (!fableFieldCalibration && !fableFieldCenter) return;
      const rect = el('fableField').getBoundingClientRect();
      const x = ((event.clientX - rect.left) / rect.width) * 100;
      const y = ((event.clientY - rect.top) / rect.height) * 100;
      fableSelectedTarget = fablePointToLatLon(x, y);
      updateFableNav(fableNav);
    });

    el('fableSendTargetBtn').addEventListener('click', async () => {
      if (!fableSelectedTarget) return;
      el('fableSendTargetBtn').disabled = true;
      const response = await fetch('/api/fable/target', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(fableSelectedTarget)
      });
      const payload = await response.json().catch(() => ({}));
      if (!payload.ok) {
        updateFableNav({ error: payload.error || 'Failed to send target.' });
      }
    });

    el('fableCancelAutoBtn').addEventListener('click', async () => {
      const response = await fetch('/api/fable/cancel', { method: 'POST' });
      const payload = await response.json().catch(() => ({}));
      if (payload.ok) {
        fableSelectedTarget = null;
        updateFableNav({ mode: 'teleop', target_valid: false, target_lat: null, target_lon: null });
      } else {
        updateFableNav({ error: payload.error || 'Failed to cancel Fable auto.' });
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

    loadFableFieldCalibration();
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
        "__FABLE_FIELD_METERS__": str(FABLE_NAV_DEFAULT_FIELD_METERS),
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
        self.fable_nav_serial = None
        self.fable_nav_serial_lock = threading.Lock()
        self.fable_nav = {
            "serial_ok": False,
            "error": "",
            "mode": "teleop",
            "target_pending": False,
            "last_update_ms": 0,
            "nav_seq": 0,
            "target_seq": 0,
            "lat": None,
            "lon": None,
            "target_lat": None,
            "target_lon": None,
            "selected_lat": None,
            "selected_lon": None,
            "gps_valid": False,
            "target_valid": False,
            "link_alive": False,
            "fix_quality": 0,
            "satellites": 0,
            "hdop": None,
            "gps_age_ms": None,
            "command_age_ms": None,
            "uptime_ms": None,
        }

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
                "fable_nav": dict(self.fable_nav),
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
            "fable_nav": dict(self.fable_nav),
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

    def set_fable_nav_serial(self, ser):
        with self.fable_nav_serial_lock:
            self.fable_nav_serial = ser

    def send_fable_nav_line(self, line):
        with self.fable_nav_serial_lock:
            if self.fable_nav_serial is None:
                return False, "Fable navigation serial is not connected."
            self.fable_nav_serial.write((line.rstrip() + "\n").encode("ascii"))
            self.fable_nav_serial.flush()
            return True, ""

    def update_fable_nav(self, updates):
        with self.lock:
            self.fable_nav.update(updates)
            self.fable_nav["last_update_ms"] = int(time.time() * 1000)
            payload = dict(self.fable_nav)
        self.publish("fable_nav", payload)

    def set_fable_nav_mode(self, mode, **updates):
        updates["mode"] = mode
        self.update_fable_nav(updates)


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


def e7_to_degrees(value):
    if value is None:
        return None
    return value / 10000000.0


def degrees_to_e7(value):
    return int(round(float(value) * 10000000))


def apply_fable_nav_message(shared, message):
    if message.get("type") == "telemetry":
        flags = int(message.get("flags", 0))
        hdop_x100 = message.get("hdop_x100")
        updates = {
            "serial_ok": True,
            "error": "",
            "nav_seq": int(message.get("nav_seq", 0)),
            "target_seq": int(message.get("target_seq", 0)),
            "lat": e7_to_degrees(message.get("lat_e7")),
            "lon": e7_to_degrees(message.get("lon_e7")),
            "target_lat": e7_to_degrees(message.get("target_lat_e7")),
            "target_lon": e7_to_degrees(message.get("target_lon_e7")),
            "gps_valid": bool(flags & 0x01),
            "target_valid": bool(flags & 0x02),
            "link_alive": bool(flags & 0x10),
            "fix_quality": int(message.get("fix", 0)),
            "satellites": int(message.get("satellites", 0)),
            "hdop": None if hdop_x100 is None else float(hdop_x100) / 100.0,
            "gps_age_ms": message.get("gps_age_ms"),
            "command_age_ms": message.get("command_age_ms"),
            "uptime_ms": message.get("uptime_ms"),
        }
        with shared.lock:
            current_mode = shared.fable_nav.get("mode")
        if updates["target_valid"] and current_mode == "teleop":
            updates["mode"] = "autonomous"
        shared.update_fable_nav(updates)
        return

    if message.get("type") == "send":
        if message.get("ok"):
            shared.update_fable_nav({"serial_ok": True, "error": ""})
        else:
            shared.update_fable_nav({"serial_ok": True, "error": message.get("error", "Fable nav send failed.")})


def run_fable_nav_serial(args, shared):
    if not args.fable_nav_port:
        shared.update_fable_nav({"serial_ok": False, "error": "Fable nav ESP not connected. Start with --fable-nav-port to enable GPS telemetry."})
        return

    ser = None
    try:
        ser = serial.Serial(args.fable_nav_port, args.fable_nav_baud, timeout=0.2)
        time.sleep(1.0)
        shared.set_fable_nav_serial(ser)
        shared.update_fable_nav({"serial_ok": True, "error": ""})
        shared.publish("notice", {"message": f"Fable nav serial open: {args.fable_nav_port} @ {args.fable_nav_baud}"})

        while True:
            raw = ser.readline()
            if not raw:
                continue
            line = raw.decode("utf-8", errors="replace").strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                shared.publish("notice", {"message": f"Fable nav serial: {line}"})
                continue
            apply_fable_nav_message(shared, message)

    except Exception as exc:
        shared.update_fable_nav({"serial_ok": False, "error": str(exc)})
        shared.publish("notice", {"message": f"Fable nav serial stopped: {exc}"})
    finally:
        shared.set_fable_nav_serial(None)
        if ser is not None:
            ser.close()


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

    @app.post("/api/fable/target")
    def fable_target():
        payload = request.get_json(silent=True) or {}
        try:
            lat = float(payload["lat"])
            lon = float(payload["lon"])
        except (KeyError, TypeError, ValueError):
            return jsonify({"ok": False, "error": "lat and lon are required numbers"}), 400

        lat_e7 = degrees_to_e7(lat)
        lon_e7 = degrees_to_e7(lon)
        ok, error = shared.send_fable_nav_line(f"TARGET {lat_e7} {lon_e7}")
        if not ok:
            shared.update_fable_nav({"selected_lat": lat, "selected_lon": lon, "target_pending": True, "error": error})
            return jsonify({"ok": False, "error": error}), 503

        shared.set_fable_nav_mode(
            "autonomous",
            selected_lat=lat,
            selected_lon=lon,
            target_lat=lat,
            target_lon=lon,
            target_valid=True,
            target_pending=False,
            error="",
        )
        return jsonify({"ok": True, "lat": lat, "lon": lon})

    @app.post("/api/fable/cancel")
    def fable_cancel():
        ok, error = shared.send_fable_nav_line("CLEAR")
        if not ok:
            shared.update_fable_nav({"error": error})
            return jsonify({"ok": False, "error": error}), 503

        shared.set_fable_nav_mode(
            "teleop",
            target_pending=False,
            target_valid=False,
            selected_lat=None,
            selected_lon=None,
            target_lat=None,
            target_lon=None,
            error="",
        )
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
    parser.add_argument("--fable-nav-port", help="optional driver-side ESP32-C3 serial port for Fable GPS/ESP-NOW navigation")
    parser.add_argument("--fable-nav-baud", type=int, default=FABLE_NAV_SERIAL_BAUD)
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

    fable_nav_thread = threading.Thread(target=run_fable_nav_serial, args=(args, shared), daemon=True)
    fable_nav_thread.start()

    print(f"Dashboard: http://{args.web_host}:{args.web_port}")
    print("Press Ctrl-C to stop.")
    try:
        run_transmitter(args, shared)
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
