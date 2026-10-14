'use strict';

// Run with: node module/scripts/status-ui-smoke.test.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const htmlPath = path.resolve(__dirname, '../docs/design/diagnostics-v3.html');
const html = fs.readFileSync(htmlPath, 'utf8');
const scripts = Array.from(html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi), match => match[1]);
let passed = 0;

function test(name, run) {
  try {
    run();
    passed++;
  } catch (error) {
    error.message = name + ': ' + error.message;
    throw error;
  }
}

test('real inline scripts pass syntax checking', () => {
  assert.ok(scripts.length, 'HTML must contain an inline script');
  scripts.forEach((script, index) => {
    new vm.Script(script, { filename: htmlPath + ':script-' + (index + 1) });
  });
});

const script = scripts.find(source => /function renderModuleActive\(/.test(source));
assert.ok(script, 'activation renderer must exist in the HTML');

function extractFunction(name) {
  const start = new RegExp('^  function ' + name + '\\(', 'm').exec(script);
  assert.ok(start, 'missing real function: ' + name);
  const rest = script.slice(start.index);
  const next = /\n  (?:async )?function [\w$]+\(/.exec(rest);
  assert.ok(next, 'missing next function boundary after: ' + name);
  // Keep adjacent declarations from the HTML too (notably STATE_LABELS).
  return rest.slice(0, next.index);
}

const functionSource = [
  'byId', 'number', 'fmtClock', 'setPill', 'statusTier', 'displayStatus',
  'statusSubtext', 'clear', 'renderStatuses', 'renderModuleActive',
  'appendInfoRow', 'scopeMark', 'frameworkText', 'renderEnvironment', 'applySnapshot'
].map(extractFunction).join('\n') + '\nlet lastSnapshot = null;';
const rendererScript = new vm.Script(functionSource, { filename: htmlPath + ':extracted-renderers' });

class Element {
  constructor(tagName) {
    this.tagName = tagName;
    this.children = [];
    this._text = '';
    this._className = '';
    this.classWrites = [];
    this.classList = {
      add: name => {
        this.className = Array.from(new Set(this.className.split(/\s+/).filter(Boolean).concat(name))).join(' ');
      },
      remove: name => {
        this.className = this.className.split(/\s+/).filter(token => token && token !== name).join(' ');
      }
    };
  }

  get className() { return this._className; }
  set className(value) {
    this._className = String(value);
    this.classWrites.push(this._className);
  }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set textContent(value) {
    this._text = String(value);
    this.children = [];
  }
  get firstChild() { return this.children[0] || null; }
  append(...children) { this.children.push(...children); }
  appendChild(child) { this.append(child); return child; }
  removeChild(child) {
    const index = this.children.indexOf(child);
    assert.ok(index >= 0, 'removeChild must receive an existing child');
    this.children.splice(index, 1);
    return child;
  }
  addEventListener() {}
}

function harness() {
  const elements = new Map([
    'module-active-pill', 'module-active-detail', 'environment-grid',
    'status-grid', 'diagnostics-page'
  ].map(id => [id, new Element('div')]));
  const switches = new Map([
    'input[data-enabled]', 'input[data-hide-launcher]', 'input[data-governor-enabled]'
  ].map(selector => [selector, new Element('input')]));
  const document = {
    getElementById: id => elements.get(id) || null,
    querySelector: selector => switches.get(selector) || null,
    createElement: tag => new Element(tag),
    createTextNode: text => {
      const node = new Element('#text');
      node.textContent = text;
      return node;
    }
  };
  // Unrelated cards are outside this test; all tested renderers come from HTML.
  const noop = () => {};
  const api = vm.createContext({
    document, showNote: noop, renderRecording: noop, renderBqr: noop,
    renderKeyStatus: noop, renderMemory: noop, renderEvents: noop
  });
  rendererScript.runInContext(api, { timeout: 1000 });
  return { api, elements, switches, pill: elements.get('module-active-pill') };
}

// Activation states are backend decisions; fixtures do not recompute them.
function snapshot(state, extra = {}) {
  return {
    enabled: true,
    activation: { state },
    environment: { module: '2.4.1 (26)', system: 'ColorOS' },
    statuses: [],
    ...extra
  };
}

function assertPill(h, tier, text, pulse = false) {
  assert.equal(h.pill.className, 'pill ' + tier);
  assert.equal(h.pill.textContent, text);
  assert.equal(h.pill.children.length, 2);
  assert.equal(h.pill.children[0].className, pulse ? 'pulse' : 'dot');
}

function environmentRow(h, label) {
  const row = h.elements.get('environment-grid').children.find(item => item.children[0].textContent === label);
  assert.ok(row, 'missing environment row: ' + label);
  return row;
}

test('setPill replaces old content and clears checking pulse', () => {
  const h = harness();
  h.api.setPill(h.pill, 'idle', '正在检测', true);
  assertPill(h, 'idle', '正在检测', true);
  h.api.setPill(h.pill, 'ok', '已激活', false);
  assertPill(h, 'ok', '已激活');
});

test('first active snapshot needs no host report', () => {
  const h = harness();
  h.api.applySnapshot(snapshot('active', {
    statuses: [{ key: 'scope.bluetooth', status: 'loaded', source: 'live' }],
    live: { scopes: { bluetooth: { loaded: true } } }
  }));
  assertPill(h, 'ok', '已激活');
  assert.equal(h.elements.get('module-active-detail').textContent, '当前版本：2.4.1 · 连接耳机即可使用');
});

test('framework enabled alone activates the main pill', () => {
  const h = harness();
  h.api.applySnapshot(snapshot('enabled', {
    framework: { bound: true, framework: 'LSPosed', frameworkVersion: '2.0', api: 101 }
  }));
  assertPill(h, 'ok', '已激活');
  assert.equal(environmentRow(h, '框架').children[1].textContent, 'LSPosed 2.0 · API 101');
});

test('disconnecting headphones and clearing statuses preserves activation', () => {
  const h = harness();
  h.api.applySnapshot(snapshot('active', {
    statuses: [{ key: 'scope.host', status: 'loaded', source: 'live' }],
    live: { scopes: { host: { loaded: true } } }
  }));
  h.api.applySnapshot(snapshot('active', { statuses: [], live: { scopes: {} } }));
  assertPill(h, 'ok', '已激活');
  assert.equal(h.elements.get('status-grid').children.length, 0);
});

test('each fresh active or enabled snapshot stays green without intermediate gray', () => {
  const h = harness();
  const frames = [
    snapshot('active'),
    snapshot('enabled', { statuses: [{ key: 'scope.host', status: 'on_demand', source: 'waiting' }] }),
    snapshot('active', { statuses: [{ key: 'scope.host', status: 'offline', source: 'offline' }] }),
    snapshot('enabled', { statuses: [] })
  ];
  frames.forEach(frame => {
    h.api.applySnapshot(frame);
    assertPill(h, 'ok', '已激活');
  });
  assert.equal(h.pill.classWrites.length, frames.length);
  assert.ok(h.pill.classWrites.every(value => value === 'pill ok'), 'even intermediate class writes must stay green');
});

test('master disabled wins over previous loaded host evidence', () => {
  const h = harness();
  h.api.applySnapshot(snapshot('active'));
  h.api.applySnapshot(snapshot('disabled', {
    enabled: false,
    statuses: [{ key: 'scope.host', status: 'loaded', source: 'confirmed' }]
  }));
  assertPill(h, 'idle', '已停用');
  assert.equal(h.switches.get('input[data-enabled]').checked, false);
  assert.match(h.elements.get('module-active-detail').textContent, /模块总开关已关闭/);
});

test('old scope requiring restart is a warning rather than activation', () => {
  const h = harness();
  h.api.applySnapshot(snapshot('restart_required', {
    statuses: [{ key: 'scope.host', status: 'restart_required', source: 'live' }]
  }));
  assertPill(h, 'warn', '待重启作用域');
  assert.match(h.elements.get('module-active-detail').textContent, /安装后请重启作用域/);
});

test('first checking snapshot never claims activation from host or framework data', () => {
  const h = harness();
  h.api.applySnapshot(snapshot('checking', {
    framework: { bound: true, framework: 'LSPosed', api: 101 },
    statuses: [{ key: 'scope.host', status: 'ok', source: 'stale' }]
  }));
  assertPill(h, 'idle', '正在检测', true);
  assert.ok(h.pill.classWrites.every(value => value !== 'pill ok'));
});

test('missing initial activation stays checking', () => {
  const h = harness();
  h.api.applySnapshot(snapshot(undefined, { activation: undefined }));
  assertPill(h, 'idle', '正在检测', true);
});

test('completed query with no activation evidence shows a warning', () => {
  const h = harness();
  h.api.applySnapshot(snapshot('unconfirmed', {
    framework: { bound: false }, live: { scopes: {} }, statuses: []
  }));
  assertPill(h, 'warn', '待启用或重启');
  assert.match(h.elements.get('module-active-detail').textContent, /请在 LSPosed 启用模块并重启作用域/);
});

test('framework service takes priority over scope reports and includes API', () => {
  const h = harness();
  const live = { scopes: { host: { framework: 'ScopeFramework', frameworkVersion: 'old', api: 100 } } };
  const framework = { bound: true, framework: 'ServiceFramework', frameworkVersion: 'new', api: 101 };
  assert.equal(h.api.frameworkText(live, framework), 'ServiceFramework new · API 101');
  h.api.renderEnvironment({ module: '2.4.1 (26)', settings: '16.0' }, [], live, framework);
  assert.equal(environmentRow(h, '框架').children[1].textContent, 'ServiceFramework new · API 101');
  assert.deepEqual(h.elements.get('environment-grid').children.map(row => row.children[0].textContent),
    ['模块版本', '系统', '框架', '无线耳机', '蓝牙', '无线设置', '系统设置']);
});

test('framework falls back to scope reports including the fourth scope', () => {
  const h = harness();
  const live = { scopes: { settings: { framework: 'SettingsFramework', frameworkVersion: '1.0', api: 101 } } };
  assert.equal(h.api.frameworkText(live, { bound: false, framework: 'IgnoredFramework' }), 'SettingsFramework 1.0 · API 101');
  assert.equal(h.api.frameworkText({}, {}), '');
});

test('enabled status renders a green badge and green scope check marks', () => {
  const h = harness();
  assert.equal(h.api.displayStatus({ status: 'enabled' }), '已启用');
  assert.equal(h.api.statusTier(h.api.displayStatus({ status: 'enabled' })), 'ok');
  assert.deepEqual(Array.from(h.api.scopeMark('enabled', 'framework')), ['ok', '✓']);
  h.api.applySnapshot(snapshot('enabled', {
    statuses: ['host', 'bluetooth', 'wirelesssettings', 'settings'].map(scope => ({
      key: 'scope.' + scope, status: 'enabled', source: 'framework'
    }))
  }));
  for (const row of h.elements.get('status-grid').children) {
    assert.equal(row.children[1].className, 'pill ok');
    assert.equal(row.children[1].textContent, '已启用');
  }
  for (const label of ['无线耳机', '蓝牙', '无线设置', '系统设置']) {
    const mark = environmentRow(h, label).children[1].children[1];
    assert.equal(mark.className, 'scope-mark ok');
    assert.equal(mark.textContent, '✓');
  }
});

test('loaded, on-demand and restart statuses stay distinct', () => {
  const h = harness();
  assert.equal(h.api.statusTier('loaded'), 'ok');
  assert.equal(h.api.displayStatus({ status: 'on_demand' }), '按需运行');
  assert.equal(h.api.statusTier(h.api.displayStatus({ status: 'on_demand' })), 'idle');
  assert.deepEqual(Array.from(h.api.scopeMark('on_demand', 'waiting')), ['idle', '—']);
  assert.equal(h.api.displayStatus({ status: 'restart_required' }), '待重启作用域');
  assert.equal(h.api.statusTier(h.api.displayStatus({ status: 'restart_required' })), 'warn');
});

console.log(passed + ' status UI smoke tests passed; ' + scripts.length + ' real inline script(s) passed syntax checking.');
