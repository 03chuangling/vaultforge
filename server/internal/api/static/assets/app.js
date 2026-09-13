// VaultForge Console —— 应用入口（路由 / 鉴权 / 生命周期）
window.App = {
  S: { me: null, info: null, settings: {}, timers: [] },

  timer(fn, ms) {
    const id = setInterval(fn, ms);
    this.S.timers.push(id);
    return id;
  },
  clearTimers() {
    this.S.timers.forEach(function (id) { clearInterval(id); });
    this.S.timers = [];
  },

  parse() {
    const raw = location.hash.slice(1) || '/dash';
    const qi = raw.indexOf('?');
    const pathPart = qi >= 0 ? raw.slice(0, qi) : raw;
    const queryPart = qi >= 0 ? raw.slice(qi + 1) : '';
    return { segs: pathPart.split('/').filter(Boolean), params: new URLSearchParams(queryPart) };
  },

  goto(hash, force) {
    if (location.hash === hash) { if (force) this.render(); }
    else location.hash = hash;
  },

  async loadSettings() {
    try {
      const s = await API.get('/settings');
      this.S.settings = s.settings || {};
    } catch (e) { /* ignore */ }
  },

  setNav(name) {
    document.querySelectorAll('#nav a').forEach(function (a) {
      const n = a.dataset.nav;
      const on = n === name || ((name === 'item' || name === 'term' || name === 'files') && n === 'items');
      a.classList.toggle('active', on);
    });
  },

  updateChrome() {
    const ver = document.getElementById('ver-text');
    if (ver && this.S.info) ver.textContent = 'v' + this.S.info.version;
    const ub = document.getElementById('userbox');
    UI.clear(ub);
    if (this.S.me) {
      ub.append(UI.h('span', { class: 'user-name' }, this.S.me.username));
      const self = this;
      ub.append(UI.h('button', { class: 'btn btn-sm btn-ghost', onclick: function () { self.logout(); } }, '退出'));
    } else {
      ub.append(UI.h('span', { class: 'muted' }, '未登录'));
    }
  },

  async logout() {
    try { await API.post('/auth/logout'); } catch (e) { /* ignore */ }
    API.setToken('');
    this.S.me = null;
    this.updateChrome();
    this.goto('#/dash', true);
  },

  async render() {
    this.clearTimers();
    const main = document.getElementById('main');
    UI.clear(main);
    const parsed = this.parse();
    const segs = parsed.segs, params = parsed.params;
    const name = segs[0] || 'dash';
    this.setNav(name);
    if (!this.S.me) { await Views.login(main); return; }
    try {
      switch (name) {
        case 'dash': await Views.dash(main); break;
        case 'items': await Views.items(main, params); break;
        case 'item': await Views.item(main, segs[1]); break;
        case 'term': await Views.term(main, segs[1], params); break;
        case 'files': await Views.files(main, segs[1], params); break;
        case 'settings': await Views.settings(main); break;
        default: await Views.dash(main);
      }
    } catch (e) {
      if (e.status === 401) {
        API.setToken('');
        this.S.me = null;
        this.updateChrome();
        UI.toast('登录已过期，请重新登录', 'err');
        await Views.login(main);
      } else {
        UI.toast('出错了：' + e.message, 'err');
      }
    }
  },

  async boot() {
    try { this.S.info = await API.req('GET', ''); } catch (e) { /* ignore */ }
    if (API.token) {
      try {
        const me = await API.get('/auth/me');
        this.S.me = me.user;
      } catch (e) { API.setToken(''); }
    }
    if (this.S.me) await this.loadSettings();
    this.updateChrome();
    const self = this;
    window.addEventListener('hashchange', function () { self.render(); });
    await this.render();
  },
};

document.addEventListener('DOMContentLoaded', function () { App.boot(); });