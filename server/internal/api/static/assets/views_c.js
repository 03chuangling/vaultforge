// VaultForge Console —— 视图 C：Bitwarden 对接（连接 Vaultwarden / Bitwarden 拉取密码条目）
window.Views = window.Views || {};
(function () {
  const U = window.UI;

  // ================= Bitwarden 对接 =================
  Views.bitwarden = function (root) {
    const view = U.h('div', { class: 'view' });
    const state = { vault: null, filter: '' };

    view.append(
      U.h('div', { class: 'view-head' },
        U.h('div', null,
          U.h('h2', { class: 'view-title' }, 'Bitwarden 对接'),
          U.h('div', { class: 'view-sub' }, '连接 Bitwarden / Vaultwarden 服务器，拉取并解密密码条目 —— 主密码仅用于本次请求，服务端不存储、不记录'))),
    );

    // ---- 连接表单 ----
    const serverI = U.h('input', { class: 'input', value: 'https://bit.bdshjgg.com', placeholder: 'https://your-vault.example.com' });
    const emailI = U.h('input', { class: 'input', placeholder: 'you@example.com', autocomplete: 'off' });
    const passI = U.h('input', { class: 'input', type: 'password', placeholder: '主密码', autocomplete: 'new-password' });
    const runBtn = U.h('button', { class: 'btn btn-primary', onclick: run }, '连接并拉取');

    const form = U.h('div', { class: 'card', style: { marginBottom: '14px' } },
      U.h('div', { class: 'card-title' }, '连接设置'),
      U.h('div', { class: 'toolbar' },
        U.h('div', null, U.h('label', { class: 'field-label' }, '服务器'), serverI),
        U.h('div', null, U.h('label', { class: 'field-label' }, '邮箱'), emailI),
        U.h('div', null, U.h('label', { class: 'field-label' }, '主密码'), passI),
        U.h('div', { style: { display: 'flex', alignItems: 'flex-end' } }, runBtn)),
      U.h('div', { class: 'view-sub', style: { marginTop: '8px' } },
        '说明：目标账号若启用了两步验证（2FA），当前暂不支持；主密码校验通过后本页会解密并展示你的密码库。'));
    view.append(form);

    const resultBox = U.h('div', null);
    view.append(resultBox);
    root.appendChild(view);

    // ---- 拉取 ----
    async function run() {
      const server = serverI.value.trim();
      const email = emailI.value.trim();
      const pw = passI.value;
      if (!server || !email || !pw) return U.toast('请填写服务器、邮箱与主密码', 'err');
      const old = runBtn.textContent;
      runBtn.disabled = true;
      runBtn.textContent = '连接中…';
      try {
        const data = await API.post('/bitwarden/pull', { server: server, email: email, password: pw });
        state.vault = data;
        state.filter = '';
        U.toast('已载入 ' + data.count + ' 条条目');
        renderResult();
      } catch (e) {
        U.toast(e.message, 'err');
      } finally {
        runBtn.disabled = false;
        runBtn.textContent = old;
      }
    }

    // ---- 结果渲染 ----
    function renderResult() {
      U.clear(resultBox);
      const v = state.vault;
      if (!v) return;
      const listBox = U.h('div', { class: 'list' });
      const searchI = U.h('input', { class: 'input', placeholder: '搜索名称 / 用户名 / 网址 / 文件夹…' });
      searchI.addEventListener('input', function () { state.filter = searchI.value.toLowerCase(); renderList(); });

      function match(e) {
        if (!state.filter) return true;
        const hay = [e.name, e.username, (e.uris || []).join(' '), e.folderName, e.notes, e.typeName].join(' ').toLowerCase();
        return hay.indexOf(state.filter) >= 0;
      }
      function renderList() {
        U.clear(listBox);
        const items = (v.entries || []).filter(match);
        if (!items.length) {
          listBox.append(U.h('div', { class: 'muted', style: { padding: '12px 4px' } }, '没有匹配的条目'));
          return;
        }
        items.forEach(function (e) { listBox.append(rowEl(e)); });
      }

      resultBox.append(
        U.h('div', { class: 'view-head' },
          U.h('div', null,
            U.h('h3', null, '已载入 ' + v.count + ' 条条目'),
            U.h('div', { class: 'view-sub' },
              '账号 ' + ((v.profile && v.profile.email) || '') + (v.profile && v.profile.name ? ' · ' + v.profile.name : '') +
              ' · ' + ((v.folders || []).length) + ' 个文件夹')),
          U.h('div', { class: 'toolbar-group' },
            searchI,
            U.h('button', { class: 'btn', onclick: run }, '⟳ 重新拉取'),
            U.h('button', { class: 'btn btn-ghost', onclick: function () { state.vault = null; passI.value = ''; U.clear(resultBox); } }, '断开'))),
        U.h('div', { class: 'view-sub', style: { marginBottom: '10px' } }, '密码默认打码显示，点「👁」查看、「复制」写入剪贴板。'),
        listBox);
      renderList();
    }

    function rowEl(e) {
      const passSpan = U.h('span', null, e.password ? '••••••••' : '（无）');
      const eyeBtn = U.h('button', { class: 'btn btn-sm btn-ghost', onclick: function () {
        if (!e.password) return;
        passSpan.textContent = passSpan.textContent === e.password ? '••••••••' : e.password;
      } }, '👁');
      const copyBtn = U.h('button', { class: 'btn btn-sm btn-ghost', onclick: function () {
        if (!e.password) return;
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(e.password).then(function () { U.toast('已复制密码'); }, function () { U.toast('复制失败', 'err'); });
        } else { U.toast('浏览器不支持剪贴板', 'err'); }
      } }, '复制');
      return U.h('div', { class: 'row' },
        U.h('div', { class: 'row-main' },
          U.h('div', { class: 'row-title' }, (e.favorite ? '⭐ ' : '') + (e.name || '(未命名)')),
          U.h('div', { class: 'row-meta' },
            U.h('span', { class: 'badge t-api' }, e.typeName || '条目'),
            e.username ? U.h('span', { class: 'muted' }, '👤 ' + e.username) : null,
            e.folderName ? U.h('span', { class: 'muted' }, '📁 ' + e.folderName) : null,
            (e.uris && e.uris[0]) ? U.h('span', { class: 'muted' }, '🔗 ' + e.uris[0]) : null)),
        U.h('div', { class: 'row-actions' },
          passSpan, eyeBtn, copyBtn,
          U.h('button', { class: 'btn btn-sm', onclick: function () { openDetail(e); } }, '详情')));
    }

    function openDetail(e) {
      const body = U.h('div', null);
      function kv(k, v) {
        if (v === undefined || v === null || v === '') return;
        body.append(U.h('div', { style: { margin: '7px 0' } },
          U.h('span', { class: 'muted' }, k + '：'),
          U.h('span', { style: { whiteSpace: 'pre-wrap', wordBreak: 'break-all' } }, v)));
      }
      kv('名称', e.name);
      kv('类型', e.typeName);
      kv('用户名', e.username);
      kv('密码', e.password);
      kv('验证码密钥（TOTP）', e.totp);
      kv('网址', (e.uris || []).join('\n'));
      kv('文件夹', e.folderName);
      kv('备注', e.notes);
      U.modal(e.name || '条目详情', body, { wide: true });
    }
  };
})();
