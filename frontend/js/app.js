/** TaskSphere global bootstrap */
window.TaskSphere = window.TaskSphere || {};

document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('[data-ts-home]').forEach((el) => {
    el.addEventListener('click', () => {
      window.location.href = 'index.html';
    });
  });
});
