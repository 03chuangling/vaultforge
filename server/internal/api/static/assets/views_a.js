// VaultForge Console —— 视图 A：登录 / 仪表盘 / 条目列表 / 条目表单
window.Views = window.Views || {};
(function () {
  const U = window.UI;

  // ================= 登录 / 注册 =================
  Views.login = function (root) {
    const info = (App.S && App.S.info) || {};
    const mode = { v: 'login' };
    const card = U.h('div', { class: 'login-card' });

    function render() {
      U.clear(card);
      const isReg = mode.v === 'register';
      const userI = U.h('input', { class: 'input', placeholder: '3-32 位字母 / 数字 / _-', autocomplete: 'username' });
      const passI = U.h('input', { class: 'input', type: 'password', placeholder: '至少 8 位', autocomplete: isReg ? 'new-password' : 'current-password' });
      card.append(
        U.h('h1', { class: 'login-title' }, 'VaultForge Console'),
        U.h('p', { class: 'login-sub' }, isReg ? '创建管理员账号（首次使用）' : '登录以管理你的秘钥仓'),
        U.h('div', { class: 'field' }, U.h('label', { class: 'field-label' }, '用户名'), userI),
        U.h('div', { class: 'field' }, U.h('label', { class: 'field-label' }, '密码'), passI),
        U.h('button', { class: 'btn btn-primary login-btn', onclick: submit }, isReg ? '注册并进入' : '登录'),
        U.h('div', { class: 'login-hint' },
          isReg
            ? U.h('span', { class: 'link', onclick: function () { mode.v = 'login'; render(); } }, '已有账号？去登录')
            : (info.register === 'open' ? U.h('span', { class: 'link', onclick: function () { mode.v = 'register'; render(); } }, '首次使用？注册管理员账号') : null)));
      setTimeout(function () { userI.focus(); }, 30);

      async function submit() {
        const u = userI.value.trim();
        const p = passI.value;
        if (!u || !p) return U.toast('请填写用户名和密码', 'err');
        try {
          const data = isReg
            ? await API.post('/auth/register', { username: u, password: p })
            : await API.post('/auth/login', { username: u, password: p });
          API.setToken(data.token);
          App.S.me = data.user;
          await App.loadSettings();
          U.toast('欢迎，' + data.user.username);
          App.updateChrome();
          App.goto('#/dash', true);
        } catch (e) { U.toast(e.message, 'err'); }
      }
    }
    render();
    root.appendChild(U.h('div', { class: 'login-wrap' }, card));
  };

  // ================= 仪表盘 =================
  Views.dash = async function (root) {
    const view = U.h('div', { class: 'view' });
    view.append(U.h('div', { class: 'loading' }, '载入中…'));
    root.appendChild(view);

    let data;
    try { data = await API.get('/stats'); }
    catch (e) { U.clear(view); view.append(U.h('div', { class: 'error-box' }, '加载失败：' + e.message)); return; }

    const it = data.items || {}, sv = data.server || {};
    const byT = it.byType || {}, byS = it.byStatus || {};

    U.clear(view);
    view.append(
      U.h('div', { class: 'view-head' },
        U.h('div', null,
          U.h('h2', { class: 'view-title' }, '仪表盘'),
          U.h('div', { class: 'view-sub' }, '条目更新于 ' + U.fmtTime(it.lastUpdatedAt))),
        U.h('div', { class: 'toolbar-group' },
          U.h('button', { class: 'btn btn-primary', onclick: function () { App.goto('#/items?new=1'); } }, '＋ 新建条目'),
          U.h('button', { class: 'btn', onclick: function () { runCheckAll(); } }, '⟳ 全量巡检'),
          U.h('button', { class: 'btn', onclick: function () { exportData(); } }, '⬇ 数据导出'),
          U.h('button', { class: 'btn btn-ghost', onclick: function () { App.render(); } }, '刷新'))),
      U.h('div', { class: 'cards-grid' },
        statCard('条目总数', it.total || 0, 'file ' + (byT.file || 0) + ' · ssh ' + (byT.ssh || 0) + ' · api ' + (byT.api || 0)),
        statusCard(byS),
        statCard('账号 / 会话', (sv.accounts || 0) + ' / ' + (sv.sessions || 0), '注册状态：' + (sv.register === 'open' ? '开放' : '关闭')),
        statCard('数据大小', U.fmtBytes(sv.dataSize), '运行时长 ' + U.fmtUptime(sv.uptimeMs)),
        statCard('服务版本', 'v' + (sv.version || '?'), '认证方式：账号登录')),
      tagsSection(it.tags));
  };

  function statCard(title, big, sub) {
    return U.h('div', { class: 'card' },
      U.h('div', { class: 'card-title' }, title),
      U.h('div', { class: 'card-big' }, big),
      U.h('div', { class: 'card-sub' }, sub));
  }

  function statusCard(byS) {
    function chip(label, n, cls) { return U.h('span', { class: 'pill ' + cls }, label + ' ' + (n || 0)); }
    const total = (byS.up || 0) + (byS.warn || 0) + (byS.down || 0) + (byS.unknown || 0);
    return U.h('div', { class: 'card' },
      U.h('div', { class: 'card-title' }, '巡检状态'),
      U.h('div', { class: 'card-big' }, (byS.up || 0) + ' / ' + total),
      U.h('div', { class: 'stat-chips' },
        chip('正常', byS.up, 'st-up'), chip('偏慢', byS.warn, 'st-warn'),
        chip('故障', byS.down, 'st-down'), chip('未测', byS.unknown, 'st-unknown')));
  }

  function tagsSection(tags) {
    if (!tags || !tags.length) return null;
    return U.h('div', { class: 'card' },
      U.h('div', { class: 'card-title', style: { marginBottom: '8px' } }, '热门标签'),
      U.h('div', { class: 'stat-chips' },
        tags.map(function (t) {
          return U.h('span', {
            class: 'tag', style: { cursor: 'pointer' },
            onclick: function () { App.goto('#/items?tag=' + encodeURIComponent(t.name)); },
          }, t.name + ' ×' + t.count);
        })));
  }

  async function runCheckAll() {
    try {
      const items = (await API.get('/items')) || [];
      if (!items.length) return U.toast('没有可巡检的条目', 'info');
      U.toast('开始巡检 ' + items.length + ' 个条目…', 'info');
      const res = await API.post('/items/batch', { action: 'check', ids: items.map(function (i) { return i.id; }) });
      const list = res.results || [];
      const okN = list.filter(function (r) { return r.ok; }).length;
      U.toast('巡检完成：' + okN + ' 正常 / ' + (list.length - okN) + ' 异常', okN === list.length ? 'ok' : 'err');
      App.render();
    } catch (e) { U.toast('巡检失败：' + e.message, 'err'); }
  }

  async function exportData() {
    try {
      U.toast('正在导出…', 'info');
      await API.download('/export', 'vaultforge-export-' + Date.now() + '.json');
    } catch (e) { U.toast('导出失败：' + e.message, 'err'); }
  }

  // ================= 条目列表 =================
  Views.items = async function (root, params) {
    params = params || new URLSearchParams();
    const state = {
      q: params.get('q') || '',
      type: params.get('type') || '',
      tag: params.get('tag') || '',
      batch: false,
      sel: new Set(),
    };
    const view = U.h('div', { class: 'view' });
    root.appendChild(view);

    const search = U.h('input', { class: 'input', placeholder: '搜索名称…', value: state.q });
    const typeSel = U.h('select', { class: 'select' },
      U.opt('', '全部类型'), U.opt('file', '文件'), U.opt('ssh', 'SSH'), U.opt('api', 'API'));
    typeSel.value = state.type;
    const tagInput = U.h('input', { class: 'input', placeholder: '标签', value: state.tag });
    let debounce = null;
    search.oninput = function () { clearTimeout(debounce); debounce = setTimeout(function () { state.q = search.value.trim(); load(); }, 260); };
    typeSel.onchange = function () { state.type = typeSel.value; load(); };
    tagInput.onchange = function () { state.tag = tagInput.value.trim(); load(); };

    const batchBtn = U.h('button', { class: 'btn', onclick: toggleBatch }, '批量');
    const bar = U.h('div', { class: 'actionbar hidden' });
    const listBox = U.h('div', { class: 'list' });
    let current = [];

    view.append(
      U.h('div', { class: 'view-head' },
        U.h('div', null,
          U.h('h2', { class: 'view-title' }, '条目'),
          U.h('div', { class: 'view-sub' }, '搜索 / 筛选 / 检查 / 批量操作')),
        U.h('div', { class: 'toolbar-group' },
          U.h('button', { class: 'btn btn-primary', onclick: function () { openCreate(); } }, '＋ 新建条目'),
          batchBtn,
          U.h('button', { class: 'btn btn-ghost', onclick: function () { load(); } }, '刷新'))),
      U.h('div', { class: 'toolbar' }, search, typeSel, tagInput),
      bar, listBox);

    async function load() {
      U.clear(listBox);
      listBox.append(U.h('div', { class: 'loading' }, '载入中…'));
      const qs = new URLSearchParams();
      if (state.q) qs.set('q', state.q);
      if (state.type) qs.set('type', state.type);
      if (state.tag) qs.set('tag', state.tag);
      try {
        current = (await API.get('/items' + (qs.toString() ? '?' + qs : ''))) || [];
        renderList();
      } catch (e) {
        U.clear(listBox);
        listBox.append(U.h('div', { class: 'error-box' }, '加载失败：' + e.message));
      }
    }

    function renderList() {
      U.clear(listBox);
      if (!current.length) { listBox.append(U.h('div', { class: 'empty' }, '没有匹配的条目')); updateBar(); return; }
      current.forEach(function (it) { listBox.append(row(it)); });
      updateBar();
    }

    function row(it) {
      const st = U.statusOf(it);
      const cb = U.h('input', { type: 'checkbox', class: 'cb' });
      cb.checked = state.sel.has(it.id);
      cb.onchange = function () {
        if (cb.checked) state.sel.add(it.id); else state.sel.delete(it.id);
        updateBar();
      };
      const r = U.h('div', { class: 'row' },
        state.batch ? cb : null,
        U.h('div', { class: 'row-main' },
          U.h('div', { class: 'row-title' }, it.name || '(未命名)'),
          U.h('div', { class: 'row-meta' },
            U.statusPill(st), U.typeBadge(it.type),
            (it.tags || []).map(function (t) { return U.h('span', { class: 'tag' }, t); }),
            U.h('span', { class: 'muted' }, '延迟 ' + U.fmtLat(it.lastLatencyMs)),
            U.h('span', { class: 'muted' }, '更新 ' + U.fmtAgo(it.updatedAt)))),
        U.h('div', { class: 'row-actions' },
          U.h('button', { class: 'btn btn-sm', onclick: function (e) { e.stopPropagation(); checkOne(it); } }, '检测')));
      r.onclick = function (e) {
        if (state.batch) {
          if (e.target === cb) return;
          cb.checked = !cb.checked;
          cb.onchange();
          return;
        }
        if (e.target.closest('.row-actions')) return;
        App.goto('#/item/' + it.id);
      };
      return r;
    }

    async function checkOne(it) {
      try {
        const res = await API.post('/items/' + it.id + '/check');
        const r = res.result || {};
        U.toast(r.ok ? '检测通过（' + r.latencyMs + ' ms）' : '检测失败：' + (r.message || ''), r.ok ? 'ok' : 'err');
        load();
      } catch (e) { U.toast('检测失败：' + e.message, 'err'); }
    }

    function toggleBatch() {
      state.batch = !state.batch;
      batchBtn.classList.toggle('active', state.batch);
      renderList();
    }

    function updateBar() {
      U.clear(bar);
      if (!state.batch) { bar.classList.add('hidden'); return; }
      bar.classList.remove('hidden');
      bar.append(
        U.h('span', { class: 'muted' }, '已选 ' + state.sel.size + ' 项'),
        U.h('div', { class: 'toolbar-group' },
          U.h('button', { class: 'btn btn-sm', onclick: function () { batchAction('check'); } }, '巡检所选'),
          U.h('button', { class: 'btn btn-sm', onclick: function () { batchAction('tag'); } }, '打标签'),
          U.h('button', { class: 'btn btn-sm', onclick: function () { batchAction('untag'); } }, '移除标签'),
          U.h('button', { class: 'btn btn-sm btn-danger', onclick: function () { batchAction('delete'); } }, '删除'),
          U.h('button', { class: 'btn btn-sm btn-ghost', onclick: function () { state.sel.clear(); toggleBatch(); } }, '取消')));
    }

    async function batchAction(action) {
      if (!state.sel.size) return U.toast('请先选择条目', 'err');
      const ids = Array.from(state.sel);
      if (action === 'delete' && !confirm('确认删除 ' + ids.length + ' 个条目？')) return;
      let tags = [];
      if (action === 'tag' || action === 'untag') {
        const v = prompt('输入标签（逗号分隔）');
        if (!v) return;
        tags = v.split(/[,，\s]+/).filter(Boolean);
        if (!tags.length) return;
      }
      try {
        const res = await API.post('/items/batch', { action: action, ids: ids, tags: tags });
        if (action === 'check') {
          const list = res.results || [];
          const okN = list.filter(function (r) { return r.ok; }).length;
          U.toast('巡检完成：' + okN + ' 正常 / ' + (list.length - okN) + ' 异常', okN === list.length ? 'ok' : 'err');
        } else {
          U.toast('已处理 ' + (res.affected || 0) + ' 个条目');
          state.sel.clear();
        }
        load();
      } catch (e) { U.toast('操作失败：' + e.message, 'err'); }
    }

    function openCreate() {
      Views.openItemModal(null, '新建条目', function () { App.goto('#/items', true); });
    }

    await load();
    if (params.get('new') === '1') {
      openCreate();
      history.replaceState(null, '', '#/items');
    }
  };

  // ================= 条目表单（新建 / 编辑共用） =================
  Views.itemForm = function (item) {
    const it = item || {};
    const typeSel = U.h('select', { class: 'select', id: 'f-type' },
      U.opt('file', '文件 (file)'), U.opt('ssh', 'SSH'), U.opt('api', 'API'));
    typeSel.value = it.type || 'ssh';

    const body = U.h('div', { class: 'form' },
      U.h('div', { class: 'field' }, U.h('label', { class: 'field-label' }, '类型'), typeSel),
      fieldInput('名称 *', 'name', it.name || '').wrap,
      fieldInput('标签（逗号分隔）', 'tags', (it.tags || []).join(', ')).wrap);

    const dyn = U.h('div');
    body.append(dyn);

    function renderDyn() {
      U.clear(dyn);
      const t = typeSel.value;
      if (t === 'file') {
        dyn.append(
          fieldInput('协议（sftp / webdav / http / ftp）', 'protocol', it.protocol || 'webdav').wrap,
          fieldInput('地址（host:port 或完整 URL）', 'address', it.address || '').wrap,
          U.h('div', { class: 'form-row' },
            fieldInput('用户名', 'username', it.username || '').wrap,
            fieldInput('密码', 'secret', it.secret || '', 'password').wrap));
      } else if (t === 'ssh') {
        const authSel = U.h('select', { class: 'select', id: 'f-authMethod' });
        authSel.append(U.opt('password', '密码'), U.opt('key', '私钥'));
        authSel.value = it.authMethod || 'password';
        dyn.append(
          U.h('div', { class: 'form-row' },
            fieldInput('主机', 'host', it.host || '').wrap,
            fieldInput('端口', 'port', it.port ? String(it.port) : '22').wrap),
          fieldInput('用户名', 'username', it.username || '').wrap,
          U.h('div', { class: 'field' }, U.h('label', { class: 'field-label' }, '认证方式'), authSel),
          fieldInput('密码 / 私钥口令', 'secret', it.secret || '', 'password').wrap,
          U.h('div', { class: 'field' }, U.h('label', { class: 'field-label' }, '私钥（key 认证时填写）'),
            U.h('textarea', { class: 'input', id: 'f-privateKey', rows: 4, placeholder: '-----BEGIN OPENSSH PRIVATE KEY-----' }, it.privateKey || '')));
      } else {
        dyn.append(
          fieldInput('Endpoint（完整 URL）', 'endpoint', it.endpoint || '').wrap,
          fieldInput('API Key / Token', 'apiKey', it.apiKey || '').wrap,
          U.h('div', { class: 'field' }, U.h('label', { class: 'field-label' }, '演示代码（curl / python / js，可选）'),
            U.h('textarea', { class: 'input', id: 'f-demoCode', rows: 5, placeholder: 'curl -H "Authorization: Bearer xxx" https://...' }, it.demoCode || '')));
      }
    }
    typeSel.onchange = renderDyn;
    renderDyn();

    function val(id) { const el = body.querySelector('#f-' + id); return el ? el.value : ''; }
    function collect() {
      const t = typeSel.value;
      const p = {
        type: t,
        name: val('name').trim(),
        tags: val('tags').split(/[,，\s]+/).filter(Boolean),
      };
      if (t === 'file') {
        p.protocol = val('protocol').trim();
        p.address = val('address').trim();
        p.username = val('username').trim();
        p.secret = val('secret');
      } else if (t === 'ssh') {
        p.host = val('host').trim();
        p.port = Number(val('port')) || 22;
        p.username = val('username').trim();
        p.authMethod = val('authMethod') || 'password';
        p.secret = val('secret');
        p.privateKey = val('privateKey');
      } else {
        p.endpoint = val('endpoint').trim();
        p.apiKey = val('apiKey');
        p.demoCode = val('demoCode');
      }
      return p;
    }
    return { el: body, collect: collect };
  };

  function fieldInput(label, id, value, type) {
    const input = U.h('input', { class: 'input', id: 'f-' + id, value: value || '', type: type || 'text' });
    return { wrap: U.h('div', { class: 'field' }, U.h('label', { class: 'field-label' }, label), input), input: input };
  }

  Views.openItemModal = function (item, title, onSaved) {
    const f = Views.itemForm(item);
    const m = U.modal(title, f.el, {
      foot: [
        U.h('button', { class: 'btn btn-ghost', onclick: function () { m.close(); } }, '取消'),
        U.h('button', { class: 'btn btn-primary', onclick: save }, item ? '保存修改' : '创建条目'),
      ],
    });
    async function save() {
      const payload = f.collect();
      if (!payload.name) return U.toast('请填写名称', 'err');
      try {
        if (item) {
          await API.patch('/items/' + item.id, payload);
          U.toast('已保存');
        } else {
          await API.post('/items', payload);
          U.toast('已创建');
        }
        m.close();
        if (onSaved) onSaved();
      } catch (e) { U.toast(e.message, 'err'); }
    }
  };
})();
