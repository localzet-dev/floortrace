# Data and technical source notes

## OpenStreetMap / indoor

FloorTrace uses nearby OSM ways tagged `building=*` and `indoor=*`. The indoor model understands `level=*`, `indoor=room`, `indoor=corridor`, `indoor=area`, `name`, `ref`, `building:levels`, and `min_level`.

## NSPD / cadastre

The adapter posts a small EPSG:3857 polygon around the fused coordinate to the geoportal `intersects` route and requests:

- category 36368 — land plots;
- category 36369 — buildings.

The public standards around NSPD support geospatial exchange such as GeoJSON/GML and geoservices such as WMS/WMTS/WFS. The concrete web-portal route used here is isolated because its stability is not guaranteed as a public mobile SDK.

## Overture

Overture's Buildings theme is useful as a server-side/offline fallback because it conflates multiple open sources and is distributed globally in GeoParquet. It is intentionally not pulled directly by the app because the dataset is large and cloud-native rather than a low-latency mobile point-query API.

## Android positioning / Wi-Fi RTT

Android exposes raw GNSS observations through `GnssMeasurementsEvent`; FloorTrace currently records measurement counts and signal diagnostics while leaving a full pseudorange solver as a future research module.

Wi-Fi RTT (`WifiRttManager`) is implemented as an optional absolute indoor source. `anchors.json` must contain surveyed BSSID coordinates; 3+ visible anchors are trilaterated and fused with PDR/GNSS.
