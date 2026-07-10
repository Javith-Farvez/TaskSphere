/** Booking flow helpers — full UI lives in customer-app.html */
const TaskSphereBooking = {
  statuses: ['PENDING', 'ACCEPTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'],
  formatRef(id) {
    return 'TS-' + String(id).padStart(5, '0');
  },
};
