/** Map helpers — Leaflet maps embedded in dashboards */
const TaskSphereMaps = {
  defaultCenter: { lat: 13.0827, lng: 80.2707 },
  createDarkTileLayer() {
    return L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap',
    });
  },
};
