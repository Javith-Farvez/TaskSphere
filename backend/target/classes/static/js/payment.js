/** Payment helpers — Razorpay integration in customer-app.html */
const TaskSpherePayment = {
  formatINR(amount) {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(amount);
  },
};
