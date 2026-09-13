// VaultForge Console —— UI 基础件（DOM / 状态 / 格式化 / 图表 / 弹层）
window.UI = {
  // ---- DOM ----
  h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    if (attrs) {
      for (const [k, v] of Object.entries(attrs)) {
        if (v === null || v === undefined || v === false) continue;
        if (k === 'class') el.className = v;
        else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
        else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
        else if (k === 'html') el.innerHTML = v;
        else if (v === true) el.setAttribute(k, '');
        else el.setAttribute(k, v);
      }
    }
    for (const kid of kids.flat(9)) {
      if (kid === null || kid === undefined || kid === false || kid === true) continue;
      el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
    }
    return el;
  },
  clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; },
  opt(v, label) { const o = document.createElement('option'); o.value = v; o.textContent = label; return o; },

  // ---- 条目状态 / 类型 ----
  STATUS: {
    up: { label: '正常', cls: 'st-up' },
    warn: { label: '偏慢', cls: 'st-warn' },
    down: { label: '故障', cls: 'st-down' },
    unknown: { label: '未检测', cls: 'st-unknown' },
  },
  TYPES: { file: '文件', ssh: 'SSH', api: 'API' },
  statusOf(it) {
    if (!it) return UI.STATUS.unknown;
    if (!it.lastCheckedAt || it.lastOk === null || it.lastOk === undefined) return UI.STATUS.unknown;
    if (!it.lastOk) return UI.STATUS.down;
    if ((it.lastLatencyMs || 0) > 300) return UI.STATUS.warn;
    return UI.STATUS.up;
  },
  statusPill(st) { return UI.h('span', { class: 'pill ' + st.cls }, st.label); },
  typeBadge(t) { return UI.h('span', { class: 'badge t-' + t }, UI.TYPES[t] || t); },

  // ---- 格式化 ----
  fmtBytes(n) {
    if (n === undefined || n === null) return '—';
    if (n < 1024) return n + ' B';
    const u = ['KB', 'MB', 'GB', 'TB'];
    let v = n / 1024, i = 0;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return v.toFixed(v >= 100 ? 0 : 1) + ' ' + u[i];
  },
  fmtTime(ms) {
    if (!ms) return '—';
    return new Date(ms).toLocaleString('zh-CN', { hour12: false });
  },
  fmtAgo(ms) {
    if (!ms) return '—';
    const s = Math.max(0, (Date.now() - ms) / 1000);
    if (s < 60) return '刚刚';
    if (s < 3600) return Math.floor(s / 60) + ' 分钟前';
    if (s < 86400) return Math.floor(s / 3600) + ' 小时前';
    return Math.floor(s / 86400) + ' 天前';
  },
  fmtUptime(ms) {
    if (ms === undefined || ms === null) return '—';
    let s = Math.floor(ms / 1000);
    const d = Math.floor(s / 86400); s %= 86400;
    const hh = Math.floor(s / 3600); s %= 3600;
    const m = Math.floor(s / 60);
    if (d > 0) return d + ' 天 ' + hh + ' 小时';
    if (hh > 0) return hh + ' 小时 ' + m + ' 分';
    if (m > 0) return m + ' 分 ' + (s % 60) + ' 秒';
    return (s % 60) + ' 秒';
  },
  fmtLat(ms) {
    if (ms === undefined || ms === null || ms < 0) return '—';
    return ms + ' ms';
  },

  // ---- toast ----
  toast(msg, type) {
    const c = document.getElementById('toasts');
    if (!c) return;
    const t = UI.h('div', { class: 'toast toast-' + (type || 'ok') }, msg);
    c.appendChild(t);
    setTimeout(() => { t.classList.add('out'); setTimeout(() => t.remove(), 320); }, 3200);
  },

  // ---- modal ----
  modal(title, bodyEl, opts) {
    opts = opts || {};
    const root = document.getElementById('modal-root');
    const close = () => { mask.remove(); document.removeEventListener('keydown', onKey); };
    const onKey = (e) => { if (e.key === 'Escape') close(); };
    const card = UI.h('div', { class: 'modal-card' + (opts.wide ? ' wide' : '') },
      UI.h('div', { class: 'modal-head' },
        UI.h('span', { class: 'modal-title' }, title),
        UI.h('button', { class: 'icon-btn', onclick: close }, '✕')),
      UI.h('div', { class: 'modal-body' }, bodyEl),
      opts.foot ? UI.h('div', { class: 'modal-foot' }, opts.foot) : null);
    const mask = UI.h('div', { class: 'modal-mask', onclick: (e) => { if (e.target === mask) close(); } }, card);
    root.appendChild(mask);
    document.addEventListener('keydown', onKey);
    return { close, card };
  },

  // ---- 图表（Canvas） ----
  Charts: {
    ring(cv, pct, color) {
      const dpr = window.devicePixelRatio || 1;
      const w = cv.clientWidth || 120, hh = cv.clientHeight || 110;
      cv.width = w * dpr; cv.height = hh * dpr;
      const ctx = cv.getContext('2d');
      ctx.scale(dpr, dpr);
      ctx.clearRect(0, 0, w, hh);
      const cx = w / 2, cy = hh / 2, r = Math.min(w, hh) / 2 - 8;
      ctx.lineWidth = 7;
      ctx.strokeStyle = 'rgba(147, 161, 179, .18)';
      ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.stroke();
      const v = Math.min(100, Math.max(0, pct || 0));
      ctx.strokeStyle = color;
      ctx.lineCap = 'round';
      ctx.beginPath();
      ctx.arc(cx, cy, r, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * v / 100);
      ctx.stroke();
      ctx.fillStyle = '#e8edf4';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.font = '600 15px system-ui, sans-serif';
      ctx.fillText(v.toFixed(1) + '%', cx, cy);
    },
    plot(cv, history) {
      const dpr = window.devicePixelRatio || 1;
      const w = cv.clientWidth || 300, hh = cv.clientHeight || 150;
      cv.width = w * dpr; cv.height = hh * dpr;
      const ctx = cv.getContext('2d');
      ctx.scale(dpr, dpr);
      ctx.clearRect(0, 0, w, hh);
      const pad = 8;
      ctx.strokeStyle = 'rgba(147, 161, 179, .14)';
      ctx.lineWidth = 1;
      for (let i = 0; i <= 4; i++) {
        const y = pad + (hh - pad * 2) * i / 4;
        ctx.beginPath(); ctx.moveTo(pad, y); ctx.lineTo(w - pad, y); ctx.stroke();
      }
      const n = history.length;
      if (n < 2) return;
      const X = (i) => pad + (w - pad * 2) * i / (n - 1);
      const Y = (v) => hh - pad - (hh - pad * 2) * Math.min(100, Math.max(0, v || 0)) / 100;
      const series = [
        { key: 'cpuPercent', color: '#5b9dff' },
        { key: 'memUsedPercent', color: '#8b7bff' },
      ];
      for (const s of series) {
        ctx.strokeStyle = s.color;
        ctx.lineWidth = 1.6;
        ctx.beginPath();
        history.forEach((p, i) => {
          const px = X(i), py = Y(p[s.key]);
          if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
        });
        ctx.stroke();
      }
    },
  },
};
