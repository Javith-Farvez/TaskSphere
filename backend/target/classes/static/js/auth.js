/**
 * TaskSphere — Shared auth helpers & role-based routing
 */
const TaskSphereAuth = (() => {
  const TK = 'ts_jwt';
  const UK = 'ts_user';

  function saveAuth(token, user) {
    localStorage.setItem(TK, token);
    localStorage.setItem(UK, JSON.stringify(user));
  }

  function getToken() {
    return localStorage.getItem(TK);
  }

  function getUser() {
    try {
      return JSON.parse(localStorage.getItem(UK) || 'null');
    } catch {
      return null;
    }
  }

  function clearAuth() {
    localStorage.removeItem(TK);
    localStorage.removeItem(UK);
  }

  function routeByRole(user) {
    if (!user) {
      window.location.href = 'index.html';
      return;
    }
    const role = (user.role || 'CUSTOMER').toUpperCase();
    const routes = {
      CUSTOMER: 'customer-app.html',
      PROVIDER: 'provider-dashboard.html',
      ADMIN: 'admin-dashboard.html',
    };
    const target = routes[role] || 'index.html';
    if (!window.location.pathname.endsWith(target)) {
      window.location.href = target;
    }
  }

  function requireRole(expectedRole) {
    const user = getUser();
    const token = getToken();
    if (!token || !user) {
      window.location.href = 'index.html?auth=login';
      return false;
    }
    if ((user.role || '').toUpperCase() !== expectedRole.toUpperCase()) {
      routeByRole(user);
      return false;
    }
    return true;
  }

  return { saveAuth, getToken, getUser, clearAuth, routeByRole, requireRole };
})();
