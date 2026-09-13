// VaultForge Console —— 视图 B：条目详情 / 终端 / 文件 / 设置
window.Views = window.Views || {};
(function () {
  const U = window.UI;

  // ================= 条目详情 =================
  Views.item = async function (root, id) {
    const view = U.h('div', { class: 'view' });
    root.appendChild(view);
    let item;
    try { item = await API.get('/items/' + id); }
    catch (e) { view.append(U.h('div', { class: 'error-box' }, '加载失败：' + e.message)); return; }

    const settings = (App.S && App.S.settings) || {};
    let metricsTimer = null;
    const metricsHistory = [];

    view.append(
      U.h('div', { class: 'view-head' },
        U.h('div', null,
          U.h('a', { class: 'back', href: '#/items' }, '← 返回列表'),
          U.h('h2', { class: 'view-title' }, item.name),
          U.h('div', { class: 'row-meta' },
            U.statusPill(U.statusOf(item)), U.typeBadge(item.type),
            (item.tags || []).map(function (t) { return U.h('span', { class: 'tag' }, t); }))),
        U.h('div', { class: 'toolbar-group' },
          U.h('button', { class: 'btn', onclick: function () { check(); } }, '立即检测'),
          U.h('button', { class: 'btn', onclick: function () { Views.openItemModal(item, '编辑条目', function () { App.render(); }); } }, '编辑'),
          item.type === 'ssh' ? U.h('button', { class: 'btn', onclick: function () { App.goto('#/term/' + item.id); } }, '终端') : null,
          isFileCapable(item) ? U.h('button', { class: 'btn', onclick: function () { App.goto('#/files/' + item.id); } }, '文件管理') : null,
          U.h('button', { class: 'btn btn-danger', onclick: function () { del(); } }, '删除'))),
      infoCard(item),
      statusCard(item));

    if (item.type === 'ssh') {
      view.append(metricsSection());
      view.append(dockerSection());
    }

    function isFileCapable(it) {
      if (it.type === 'ssh') return true;
      const p = String(it.protocol || '').toLowerCase();
      return it.type === 'file' && (p === 'sftp' || p === 'webdav');
    }

    async function check() {
      try {
        const res = await API.post('/items/' + item.id + '/check');
        const r = res.result || {};
        U.toast(r.ok ? '检测通过（' + r.latencyMs + ' ms）' : '检测失败：' + (r.message || ''), r.ok ? 'ok' : 'err');
        App.render();
      } catch (e) { U.toast('检测失败：' + e.message, 'err'); }
    }

    async function del() {
      if (!confirm('确认删除条目「' + item.name + '」？')) return;
      try {
        await API.del('/items/' + item.id);
        U.toast('已删除');
        App.goto('#/items');
      } catch (e) { U.toast('删除失败：' + e.message, 'err'); }
    }

    function infoCard(it) {
      const kv = U.h('div', { class: 'kv' });
      function row(label, value) {
        kv.append(U.h('div', { class: 'kv-row' },
          U.h('span', { class: 'kv-key' }, label),
          U.h('span', { class: 'kv-val' }, value || '—')));
      }
      function secretRow(label, value) {
        let shown = false;
        const code = U.h('code', { class: 'kv-val' }, value ? '••••••••' : '（空）');
        const btn = U.h('button', {
          class: 'btn btn-sm btn-ghost',
          onclick: function () { shown = !shown; code.textContent = shown ? (value || '（空）') : '••••••••'; },
        }, '显示');
        kv.append(U.h('div', { class: 'kv-row' },
          U.h('span', { class: 'kv-key' }, label), code, value ? btn : null));
      }
      if (it.type === 'file') {
        row('协议', it.protocol); row('地址', it.address); row('用户名', it.username); secretRow('密码', it.secret);
      } else if (it.type === 'ssh') {
        row('主机', it.host);
        row('端口', it.port ? String(it.port) : '22');
        row('用户名', it.username);
        row('认证方式', it.authMethod === 'key' ? '私钥' : '密码');
        secretRow('密码 / 口令', it.secret);
        row('私钥', it.privateKey ? '已配置（' + String(it.privateKey).split('\n').length + ' 行）' : '—');
      } else {
        row('Endpoint', it.endpoint);
        secretRow('API Key', it.apiKey);
        if (it.demoCode) {
          kv.append(U.h('div', { class: 'kv-row' },
            U.h('span', { class: 'kv-key' }, '演示代码'),
            U.h('pre', { class: 'kv-val', style: { margin: '0', whiteSpace: 'pre-wrap' } }, it.demoCode)));
        }
      }
      return U.h('div', { class: 'card' },
        U.h('div', { class: 'card-head' }, U.h('span', { class: 'card-title' }, '条目信息')), kv);
    }

    function statusCard(it) {
      const kv = U.h('div', { class: 'kv' });
      function row(label, value) {
        kv.append(U.h('div', { class: 'kv-row' },
          U.h('span', { class: 'kv-key' }, label),
          U.h('span', { class: 'kv-val' }, value)));
      }
      row('状态', U.statusOf(it).label);
      row('延迟', U.fmtLat(it.lastLatencyMs));
      row('最近检测', U.fmtTime(it.lastCheckedAt));
      row('检测信息', it.lastMessage || '—');
      return U.h('div', { class: 'card' },
        U.h('div', { class: 'card-head' }, U.h('span', { class: 'card-title' }, '探测状态')), kv);
    }

    function metricsSection() {
      const style = settings.chartStyle === 'plot' ? 'plot' : 'ring';
      const grids = U.h('div', { class: 'metrics-grid' });
      const figs = U.h('div', { class: 'metric-figs' });
      const errBox = U.h('div', { class: 'error-box hidden' });
      const cap = U.h('div', { class: 'view-sub' }, '等待数据…');
      const autoCb = U.h('input', { type: 'checkbox' });
      autoCb.checked = true;
      const cvCPU = U.h('canvas', { class: 'ring' });
      const cvMEM = U.h('canvas', { class: 'ring' });
      const cvDisk = U.h('canvas', { class: 'ring' });
      const cvPlot = U.h('canvas', { class: 'plot' });
      if (style === 'ring') {
        grids.append(
          U.h('div', { class: 'metric-card' }, cvCPU, U.h('div', { class: 'metric-cap' }, 'CPU')),
          U.h('div', { class: 'metric-card' }, cvMEM, U.h('div', { class: 'metric-cap' }, '内存')),
          U.h('div', { class: 'metric-card' }, cvDisk, U.h('div', { class: 'metric-cap' }, '磁盘')));
      } else {
        grids.style.gridTemplateColumns = '1fr';
        grids.append(U.h('div', null, cvPlot,
          U.h('div', { class: 'metric-cap' }, 'CPU（蓝） / 内存（紫）实时曲线')));
      }
      const card = U.h('div', { class: 'card' },
        U.h('div', { class: 'card-head' },
          U.h('span', { class: 'card-title' }, 'SSH 服务器指标'),
          U.h('div', { class: 'toolbar-group' },
            U.h('label', { class: 'muted' }, autoCb, '自动刷新'),
            U.h('button', { class: 'btn btn-sm', onclick: function () { loadMetrics(); } }, '刷新'))),
        cap, errBox, grids, figs);

      autoCb.onchange = function () { if (autoCb.checked) startAuto(); else stopAuto(); };

      function startAuto() {
        stopAuto();
        const sec = Math.min(600, Math.max(0.5, Number(settings.sampleSec) || 2));
        metricsTimer = App.timer(loadMetrics, sec * 1000);
      }
      function stopAuto() { if (metricsTimer) { clearInterval(metricsTimer); metricsTimer = null; } }

      async function loadMetrics() {
        try {
          const m = await API.get('/items/' + item.id + '/metrics');
          if (!m.ok) {
            errBox.textContent = '指标获取失败：' + (m.error || '未知错误');
            errBox.classList.remove('hidden');
            return;
          }
          errBox.classList.add('hidden');
          cap.textContent = '更新于 ' + U.fmtTime(m.fetchedAt) + ' · 内核 ' + (m.kernel || '—') + ' · Load ' + (m.load1 || '—');
          metricsHistory.push(m);
          if (metricsHistory.length > 60) metricsHistory.shift();
          if (style === 'ring') {
            U.Charts.ring(cvCPU, m.cpuPercent, '#5b9dff');
            U.Charts.ring(cvMEM, m.memUsedPercent, '#8b7bff');
            U.Charts.ring(cvDisk, m.diskUsedPercent, '#3ecb84');
          } else {
            U.Charts.plot(cvPlot, metricsHistory);
          }
          U.clear(figs);
          figs.append(
            fig('内存', m.memUsedMb + ' / ' + m.memTotalMb + ' MB'),
            fig('网络 ↓ / ↑', rate(m.netRxKbps) + ' / ' + rate(m.netTxKbps)),
            fig('磁盘读 / 写', rate(m.diskReadKbps) + ' / ' + rate(m.diskWriteKbps)));
        } catch (e) {
          errBox.textContent = '指标获取失败：' + e.message;
          errBox.classList.remove('hidden');
        }
      }
      function fig(label, value) { return U.h('span', null, label + ' ', U.h('b', null, value)); }
      function rate(v) {
        if (v === undefined || v === null) return '—';
        if (v >= 1024) return (v / 1024).toFixed(1) + ' MB/s';
        return v.toFixed(1) + ' KB/s';
      }

      loadMetrics();
      startAuto();
      return card;
    }

    function dockerSection() {
      const listBox = U.h('div', null);
      const card = U.h('div', { class: 'card' },
        U.h('div', { class: 'card-head' },
          U.h('span', { class: 'card-title' }, 'Docker 容器'),
          U.h('button', { class: 'btn btn-sm', onclick: function () { load(); } }, '刷新')),
        listBox);

      async function load() {
        U.clear(listBox);
        listBox.append(U.h('div', { class: 'loading' }, '载入中…'));
        try {
          const data = await API.get('/items/' + item.id + '/docker');
          U.clear(listBox);
          if (!data.ok) {
            listBox.append(U.h('div', { class: 'error-box' }, '无法读取容器：' + (data.error || '未知错误')));
            return;
          }
          const list = data.containers || [];
          if (!list.length) { listBox.append(U.h('div', { class: 'empty' }, '没有容器')); return; }
          list.forEach(function (c) { listBox.append(drow(c)); });
        } catch (e) {
          U.clear(listBox);
          listBox.append(U.h('div', { class: 'error-box' }, e.message));
        }
      }

      function drow(c) {
        function act(label, action, danger) {
          return U.h('button', {
            class: 'btn btn-sm' + (danger ? ' btn-danger' : ''),
            onclick: async function () {
              if (action !== 'start' && !confirm('确认 ' + label + '容器 ' + c.name + '？')) return;
              try {
                const res = await API.post('/items/' + item.id + '/docker', { container: c.name, action: action });
                const out = String(res.output || '').trim();
                U.toast(out ? out.split('\n').pop() : (label + '完成'));
                load();
              } catch (e) { U.toast(e.message, 'err'); }
            },
          }, label);
        }
        return U.h('div', { class: 'docker-row' },
          U.h('div', null,
            U.h('div', { class: 'docker-name' }, c.name),
            U.h('div', { class: 'muted' }, (c.image || '') + ' · ' + (c.status || ''))),
          U.h('div', { class: 'toolbar-group' },
            act('启动', 'start'), act('停止', 'stop', true), act('重启', 'restart'),
            U.h('button', { class: 'btn btn-sm', onclick: function () { logs(c); } }, '日志'),
            U.h('button', {
              class: 'btn btn-sm',
              onclick: function () { App.goto('#/term/' + item.id + '?container=' + encodeURIComponent(c.name)); },
            }, '终端')));
      }

      async function logs(c) {
        try {
          const res = await API.post('/items/' + item.id + '/docker', { container: c.name, action: 'logs' });
          U.modal('容器日志 · ' + c.name,
            U.h('pre', { class: 'term-out', style: { margin: '0', maxHeight: '60vh' } }, res.output || '（无输出）'),
            { wide: true });
        } catch (e) { U.toast(e.message, 'err'); }
      }

      load();
      return card;
    }
  };

  // ================= 终端 =================
  Views.term = async function (root, id, params) {
    params = params || new URLSearchParams();
    const view = U.h('div', { class: 'view' });
    root.appendChild(view);
    let item;
    try { item = await API.get('/items/' + id); }
    catch (e) { view.append(U.h('div', { class: 'error-box' }, '加载失败：' + e.message)); return; }

    let mode = params.get('container') ? 'docker' : 'shell';
    const containerI = U.h('input', { class: 'input', placeholder: '容器名', value: params.get('container') || '' });
    containerI.style.width = '170px';
    const modeSel = U.h('select', { class: 'select' });
    modeSel.append(U.opt('shell', '服务器命令'), U.opt('docker', 'docker exec 容器'));
    modeSel.value = mode;
    const containerWrap = U.h('div', { class: 'toolbar-group' + (mode === 'docker' ? '' : ' hidden') },
      U.h('span', { class: 'muted' }, '容器'), containerI);
    modeSel.onchange = function () {
      mode = modeSel.value;
      containerWrap.classList.toggle('hidden', mode !== 'docker');
    };

    const out = U.h('div', { class: 'term-out' });
    const input = U.h('input', { class: 'input term-input', placeholder: '输入命令，回车执行', autocomplete: 'off' });
    const sendBtn = U.h('button', { class: 'btn btn-primary', type: 'submit' }, '执行');
    const form = U.h('form', { class: 'term-form', onsubmit: function (e) { e.preventDefault(); run(); } },
      U.h('span', { class: 'term-prompt' }, '$'), input, sendBtn);

    const quick = U.h('div', { class: 'toolbar-group' });
    ['docker ps -a', 'free -m', 'df -h', 'uptime'].forEach(function (c) {
      quick.append(U.h('button', { class: 'btn btn-sm', onclick: function () { input.value = c; run(); } }, c));
    });

    view.append(
      U.h('div', { class: 'view-head' },
        U.h('div', null,
          U.h('a', { class: 'back', href: '#/item/' + item.id }, '← 返回详情'),
          U.h('h2', { class: 'view-title' }, '终端 · ' + item.name),
          U.h('div', { class: 'view-sub' }, (item.host || '') + (item.port ? ':' + item.port : ''))),
        U.h('div', { class: 'toolbar-group' },
          modeSel, containerWrap,
          U.h('button', { class: 'btn btn-ghost btn-sm', onclick: function () { U.clear(out); } }, '清屏'))),
      U.h('div', { class: 'toolbar' }, quick),
      out, form);

    appendLine('已连接 ' + (item.host || item.name) + '，输入命令开始操作', 'muted');

    const historyArr = [];
    let hi = -1;
    input.onkeydown = function (e) {
      if (!historyArr.length) return;
      if (e.key === 'ArrowUp') {
        hi = hi <= 0 ? historyArr.length - 1 : hi - 1;
        input.value = historyArr[hi];
        e.preventDefault();
      } else if (e.key === 'ArrowDown') {
        hi = Math.min(hi + 1, historyArr.length - 1);
        input.value = historyArr[hi];
      }
    };

    async function run() {
      const cmd = input.value.trim();
      if (!cmd) return;
      const payload = { command: cmd };
      if (mode === 'docker') {
        const cname = containerI.value.trim();
        if (!cname) return U.toast('请填写容器名', 'err');
        payload.container = cname;
      }
      input.value = '';
      historyArr.push(cmd);
      hi = historyArr.length;
      appendLine('❯ ' + cmd, 'cmd');
      const els = [input, sendBtn, modeSel, containerI];
      els.forEach(function (el) { el.disabled = true; });
      try {
        const res = await API.post('/items/' + item.id + '/exec', payload);
        const text = String(res.output || '').replace(/\s+$/, '');
        appendLine(text || '（无输出）', text ? '' : 'muted');
      } catch (e) {
        appendLine('[错误] ' + e.message, 'err');
      } finally {
        els.forEach(function (el) { el.disabled = false; });
        input.focus();
      }
    }

    function appendLine(text, cls) {
      out.append(U.h('div', { class: 'term-line' + (cls ? ' ' + cls : '') }, text));
      out.scrollTop = out.scrollHeight;
    }
    input.focus();
  };

  // ================= 文件管理 =================
  Views.files = async function (root, id, params) {
    params = params || new URLSearchParams();
    const view = U.h('div', { class: 'view' });
    root.appendChild(view);
    let item;
    try { item = await API.get('/items/' + id); }
    catch (e) { view.append(U.h('div', { class: 'error-box' }, '加载失败：' + e.message)); return; }

    let cwd = params.get('path') || '/';
    const crumbs = U.h('div', { class: 'crumbs' });
    const listBox = U.h('div', null);
    const uploadInput = U.h('input', { type: 'file', class: 'hidden' });
    uploadInput.onchange = doUpload;

    view.append(
      U.h('div', { class: 'view-head' },
        U.h('div', null,
          U.h('a', { class: 'back', href: '#/item/' + item.id }, '← 返回详情'),
          U.h('h2', { class: 'view-title' }, '文件 · ' + item.name),
          U.h('div', { class: 'view-sub' }, (item.protocol || item.type) + ' · ' + (item.address || item.host || ''))),
        U.h('div', { class: 'toolbar-group' },
          U.h('button', { class: 'btn', onclick: function () { nav(parentOf(cwd)); } }, '⬆ 上级'),
          U.h('button', { class: 'btn', onclick: function () { load(); } }, '刷新'),
          U.h('button', { class: 'btn btn-primary', onclick: function () { uploadInput.click(); } }, '上传文件'),
          uploadInput)),
      U.h('div', { class: 'toolbar' }, crumbs),
      listBox);

    function nav(p) { cwd = p || '/'; updateCrumbs(); load(); }

    function updateCrumbs() {
      U.clear(crumbs);
      crumbs.append(U.h('span', { class: 'crumb', onclick: function () { nav('/'); } }, 'root'));
      let acc = '';
      cwd.split('/').filter(Boolean).forEach(function (seg) {
        acc += '/' + seg;
        const target = acc;
        crumbs.append(U.h('span', { class: 'crumb-sep' }, '/'));
        crumbs.append(U.h('span', { class: 'crumb', onclick: function () { nav(target); } }, seg));
      });
    }

    async function load() {
      U.clear(listBox);
      listBox.append(U.h('div', { class: 'loading' }, '载入中…'));
      try {
        const data = await API.get('/items/' + item.id + '/files?path=' + encodeURIComponent(cwd));
        U.clear(listBox);
        const entries = data.entries || [];
        if (cwd !== '/') listBox.append(parentRow());
        entries.forEach(function (e) { listBox.append(fileRow(e)); });
        if (!entries.length) listBox.append(U.h('div', { class: 'empty' }, '目录为空'));
      } catch (e) {
        U.clear(listBox);
        listBox.append(U.h('div', { class: 'error-box' }, '读取失败：' + e.message));
      }
    }

    function parentRow() {
      return U.h('div', { class: 'file-row', style: { cursor: 'pointer' }, onclick: function () { nav(parentOf(cwd)); } },
        U.h('span', { class: 'file-icon' }, '⬆'),
        U.h('span', { class: 'file-name' }, '..'),
        U.h('span', { class: 'file-size muted' }, ''),
        U.h('span', { class: 'file-time muted' }, ''),
        U.h('div', { class: 'file-actions' }));
    }

    function fileRow(e) {
      const row = U.h('div', { class: 'file-row', style: e.isDir ? { cursor: 'pointer' } : null },
        U.h('span', { class: 'file-icon' }, e.isDir ? '📁' : '📄'),
        U.h('span', { class: 'file-name' }, e.name),
        U.h('span', { class: 'file-size muted' }, e.isDir ? '—' : U.fmtBytes(e.size)),
        U.h('span', { class: 'file-time muted' }, U.fmtTime(e.modifiedAt)),
        U.h('div', { class: 'file-actions' },
          e.isDir ? null : U.h('button', { class: 'btn btn-sm', onclick: function (ev) { ev.stopPropagation(); dl(e); } }, '下载'),
          U.h('button', { class: 'btn btn-sm btn-danger', onclick: function (ev) { ev.stopPropagation(); rm(e); } }, '删除')));
      if (e.isDir) row.onclick = function () { nav(e.path); };
      return row;
    }

    async function dl(e) {
      try { await API.download('/items/' + item.id + '/files/download?path=' + encodeURIComponent(e.path), e.name); }
      catch (err) { U.toast('下载失败：' + err.message, 'err'); }
    }

    async function rm(e) {
      if (!confirm('确认删除 ' + (e.isDir ? '目录' : '文件') + '「' + e.name + '」？')) return;
      try {
        await API.post('/items/' + item.id + '/files/delete', { path: e.path, isDir: !!e.isDir });
        U.toast('已删除');
        load();
      } catch (err) { U.toast('删除失败：' + err.message, 'err'); }
    }

    async function doUpload() {
      const f = uploadInput.files && uploadInput.files[0];
      if (!f) return;
      if (f.size > 16 * 1024 * 1024) { U.toast('文件超过 16MB 上限', 'err'); uploadInput.value = ''; return; }
      try {
        U.toast('上传中：' + f.name, 'info');
        await API.upload(item.id, cwd, f);
        U.toast('上传完成');
        load();
      } catch (err) { U.toast('上传失败：' + err.message, 'err'); }
      finally { uploadInput.value = ''; }
    }

    updateCrumbs();
    await load();
  };

  function parentOf(p) {
    if (!p || p === '/') return '/';
    const segs = p.split('/').filter(Boolean);
    segs.pop();
    return '/' + segs.join('/');
  }

  // ================= 设置 =================
  Views.settings = async function (root) {
    const view = U.h('div', { class: 'view' });
    root.appendChild(view);

    let settings = {}, server = null, sessions = [];
    try { const s = await API.get('/settings'); settings = s.settings || {}; } catch (e) { /* ignore */ }
    try { server = (await API.get('/stats')).server; } catch (e) { /* ignore */ }
    try { const s = await API.get('/auth/sessions'); sessions = s.sessions || []; } catch (e) { /* ignore */ }

    view.append(
      U.h('div', { class: 'view-head' },
        U.h('div', null,
          U.h('h2', { class: 'view-title' }, '设置'),
          U.h('div', { class: 'view-sub' }, '面板偏好 / 账号安全 / 数据导出'))));

    // ---- 偏好 ----
    const styleSel = U.h('select', { class: 'select' });
    styleSel.append(U.opt('ring', '环形图（ring）'), U.opt('plot', '折线图（plot）'));
    styleSel.value = settings.chartStyle === 'plot' ? 'plot' : 'ring';
    const sampleI = U.h('input', { class: 'input', type: 'number', min: '0.1', max: '600', step: '0.1', value: String(settings.sampleSec || 2) });
    view.append(U.h('div', { class: 'card' },
      U.h('div', { class: 'card-head' }, U.h('span', { class: 'card-title' }, '偏好设置')),
      U.h('div', { class: 'form-row' },
        U.h('div', { class: 'field' }, U.h('label', { class: 'field-label' }, '指标图表样式'), styleSel),
        U.h('div', { class: 'field' }, U.h('label', { class: 'field-label' }, '采样间隔（秒，0.1-600）'), sampleI)),
      U.h('button', { class: 'btn btn-primary', onclick: savePrefs }, '保存偏好')));

    async function savePrefs() {
      const payload = {
        chartStyle: styleSel.value,
        sampleSec: Math.min(600, Math.max(0.1, Number(sampleI.value) || 2)),
      };
      try {
        const s = await API.put('/settings', payload);
        App.S.settings = s.settings || payload;
        U.toast('偏好已保存');
      } catch (e) { U.toast('保存失败：' + e.message, 'err'); }
    }

    // ---- 账号安全 ----
    const oldI = U.h('input', { class: 'input', type: 'password', placeholder: '原密码' });
    const newI = U.h('input', { class: 'input', type: 'password', placeholder: '新密码（8-128 位）' });
    const new2I = U.h('input', { class: 'input', type: 'password', placeholder: '重复新密码' });
    view.append(U.h('div', { class: 'card' },
      U.h('div', { class: 'card-head' }, U.h('span', { class: 'card-title' }, '账号安全')),
      U.h('div', { class: 'field' }, U.h('label', { class: 'field-label' }, '原密码'), oldI),
      U.h('div', { class: 'form-row' },
        U.h('div', { class: 'field' }, U.h('label', { class: 'field-label' }, '新密码'), newI),
        U.h('div', { class: 'field' }, U.h('label', { class: 'field-label' }, '确认新密码'), new2I)),
      U.h('button', { class: 'btn btn-primary', onclick: changePwd }, '修改密码')));

    async function changePwd() {
      if (!newI.value || newI.value.length < 8) return U.toast('新密码至少 8 位', 'err');
      if (newI.value !== new2I.value) return U.toast('两次输入的新密码不一致', 'err');
      try {
        const res = await API.post('/auth/password', { oldPassword: oldI.value, newPassword: newI.value });
        U.toast('密码已更新' + (res.revokedOthers ? '（已吊销其他 ' + res.revokedOthers + ' 个会话）' : ''));
        oldI.value = ''; newI.value = ''; new2I.value = '';
      } catch (e) { U.toast(e.message, 'err'); }
    }

    // ---- 会话 ----
    const sessBox = U.h('div', { class: 'kv' });
    view.append(U.h('div', { class: 'card' },
      U.h('div', { class: 'card-head' },
        U.h('span', { class: 'card-title' }, '登录会话（' + sessions.length + '）'),
        U.h('div', { class: 'toolbar-group' },
          U.h('button', { class: 'btn btn-sm', onclick: function () { App.render(); } }, '刷新'),
          U.h('button', { class: 'btn btn-sm btn-danger', onclick: function () { App.logout(); } }, '退出登录'))),
      sessBox));
    if (!sessions.length) sessBox.append(U.h('div', { class: 'empty' }, '无法读取会话列表'));
    sessions.forEach(function (s) {
      sessBox.append(U.h('div', { class: 'kv-row' },
        U.h('span', { class: 'kv-val' }, s.id + (s.current ? '（当前）' : '')),
        U.h('span', { class: 'muted' }, '创建 ' + U.fmtTime(s.createdAt) + ' · 过期 ' + U.fmtTime(s.expiresAt)),
        s.current ? null : U.h('button', { class: 'btn btn-sm btn-danger', onclick: function () { revoke(s.id); } }, '吊销')));
    });

    async function revoke(id) {
      if (!confirm('确认吊销该会话？对应设备将需要重新登录。')) return;
      try {
        await API.del('/auth/sessions/' + id);
        U.toast('已吊销');
        App.render();
      } catch (e) { U.toast('吊销失败：' + e.message, 'err'); }
    }

    // ---- 数据 ----
    view.append(U.h('div', { class: 'card' },
      U.h('div', { class: 'card-head' }, U.h('span', { class: 'card-title' }, '数据')),
      U.h('div', { class: 'muted', style: { marginBottom: '10px' } }, '导出全部条目与设置（JSON 下载）。上传 / 下载文件上限 16MB。'),
      U.h('div', { class: 'toolbar-group' },
        U.h('button', {
          class: 'btn',
          onclick: async function () {
            try { U.toast('正在导出…', 'info'); await API.download('/export', 'vaultforge-export-' + Date.now() + '.json'); }
            catch (e) { U.toast('导出失败：' + e.message, 'err'); }
          },
        }, '⬇ 导出全部数据'))));

    // ---- 关于 ----
    const aboutKv = U.h('div', { class: 'kv' });
    function arow(k, v) {
      aboutKv.append(U.h('div', { class: 'kv-row' },
        U.h('span', { class: 'kv-key' }, k),
        U.h('span', { class: 'kv-val' }, v)));
    }
    const sv = server || {};
    arow('版本', 'v' + (sv.version || '?'));
    arow('账号数', String(sv.accounts || 0));
    arow('会话数', String(sv.sessions || 0));
    arow('数据大小', U.fmtBytes(sv.dataSize));
    arow('运行时长', U.fmtUptime(sv.uptimeMs));
    aboutKv.append(U.h('div', { class: 'kv-row' },
      U.h('span', { class: 'kv-key' }, '仓库'),
      U.h('a', { class: 'kv-val', href: 'https://github.com/03chuangling/vaultforge', target: '_blank', rel: 'noopener' }, 'github.com/03chuangling/vaultforge')));
    view.append(U.h('div', { class: 'card' },
      U.h('div', { class: 'card-head' }, U.h('span', { class: 'card-title' }, '关于')), aboutKv));
  };
})();