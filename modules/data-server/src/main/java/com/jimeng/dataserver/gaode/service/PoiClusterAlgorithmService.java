package com.jimeng.dataserver.gaode.service;

import com.jimeng.dataserver.gaode.entity.GaoDeDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PoiClusterAlgorithmService {

    private static final int DBSCAN_NOISE = -1;
    private static final int DBSCAN_UNCLASSIFIED = 0;
    private static final int DEFAULT_DBSCAN_MIN_POINTS = 3;
    private static final double DEFAULT_DBSCAN_EPS_METERS = 3000D;
    private static final double EARTH_RADIUS_METERS = 6_371_000D;

    public GaoDeDTO.PoiClusterResp clusterPois(List<GaoDeDTO.POI> sourcePois) {
        List<GaoDeDTO.POI> pois = sourcePois == null ? Collections.emptyList() : sourcePois;
        log.info("【POI聚类】开始处理，原始POI数量={}", pois.size());
        List<GeoPoiPoint> points = new ArrayList<>();

        // 只提取聚类场景需要的字段，并过滤掉无效坐标的POI。
        for (GaoDeDTO.POI poi : pois) {
            if (poi == null || poi.getLocation() == null || poi.getLocation().isBlank()) {
                continue;
            }
            double[] lonLat = parseLonLat(poi.getLocation());
            if (lonLat == null) {
                continue;
            }
            List<GaoDeDTO.ClusterSubPoi> children = new ArrayList<>();
            if (poi.getChildren() != null) {
                for (GaoDeDTO.POIChild child : poi.getChildren()) {
                    if (child == null) {
                        continue;
                    }
                    String sname = child.getSname();
                    if (sname == null || sname.isBlank()) {
                        sname = child.getName();
                    }
                    children.add(new GaoDeDTO.ClusterSubPoi(
                            child.getLocation(),
                            child.getAddress(),
                            child.getSubtype(),
                            child.getTypecode(),
                            sname
                    ));
                }
            }
            points.add(new GeoPoiPoint(
                    lonLat[0],
                    lonLat[1],
                    new GaoDeDTO.ClusterPoi(
                            poi.getLocation(),
                            poi.getType(),
                            poi.getTypecode(),
                            poi.getAddress(),
                            children,
                            poi.getBusiness()
                    )
            ));
        }

        // 使用地理距离进行DBSCAN聚类。
        log.info("【POI聚类】有效坐标POI数量={}，DBSCAN参数: eps={}m, minPoints={}", points.size(), DEFAULT_DBSCAN_EPS_METERS, DEFAULT_DBSCAN_MIN_POINTS);
        runDbscan(points, DEFAULT_DBSCAN_EPS_METERS, DEFAULT_DBSCAN_MIN_POINTS);

        Map<Integer, List<GeoPoiPoint>> grouped = points.stream()
                .filter(point -> point.clusterId > DBSCAN_UNCLASSIFIED)
                .collect(Collectors.groupingBy(point -> point.clusterId, TreeMap::new, Collectors.toList()));

        // 组装每个簇，同时用簇内点位均值计算中心点。
        List<GaoDeDTO.PoiCluster> clusters = new ArrayList<>();
        for (Map.Entry<Integer, List<GeoPoiPoint>> entry : grouped.entrySet()) {
            List<GeoPoiPoint> clusterPoints = entry.getValue();
            double lonSum = 0D;
            double latSum = 0D;
            List<GaoDeDTO.ClusterPoi> clusterPois = new ArrayList<>();
            for (GeoPoiPoint point : clusterPoints) {
                lonSum += point.longitude;
                latSum += point.latitude;
                clusterPois.add(point.poi);
            }
            String centerLocation = String.format(
                    Locale.ROOT,
                    "%.6f,%.6f",
                    lonSum / clusterPoints.size(),
                    latSum / clusterPoints.size()
            );
            clusters.add(new GaoDeDTO.PoiCluster(entry.getKey(), centerLocation, clusterPois.size(), clusterPois));
        }

        // DBSCAN中的噪声点单独返回，便于业务侧后续处理。
        List<GaoDeDTO.ClusterPoi> noisePois = points.stream()
                .filter(point -> point.clusterId == DBSCAN_NOISE)
                .map(point -> point.poi)
                .collect(Collectors.toList());

        log.info("【POI聚类】完成，clusterCount={}，noiseCount={}，validPoiCount={}", clusters.size(), noisePois.size(), points.size());

        return new GaoDeDTO.PoiClusterResp(
                pois.size(),
                points.size(),
                clusters.size(),
                noisePois.size(),
                clusters,
                noisePois
        );
    }

    private void runDbscan(List<GeoPoiPoint> points, double epsMeters, int minPoints) {
        if (points.isEmpty()) {
            return;
        }
        int clusterId = 0;
        for (GeoPoiPoint point : points) {
            if (point.visited) {
                continue;
            }
            point.visited = true;
            List<GeoPoiPoint> neighbors = regionQuery(points, point, epsMeters);
            if (neighbors.size() < minPoints) {
                point.clusterId = DBSCAN_NOISE;
                continue;
            }

            // 从核心点开始做密度可达扩展，把同一簇的点都吸收进来。
            clusterId++;
            point.clusterId = clusterId;
            ArrayDeque<GeoPoiPoint> queue = new ArrayDeque<>(neighbors);
            Set<GeoPoiPoint> queued = new HashSet<>(neighbors);
            while (!queue.isEmpty()) {
                GeoPoiPoint current = queue.poll();
                if (!current.visited) {
                    current.visited = true;
                    List<GeoPoiPoint> currentNeighbors = regionQuery(points, current, epsMeters);
                    if (currentNeighbors.size() >= minPoints) {
                        for (GeoPoiPoint neighbor : currentNeighbors) {
                            if (queued.add(neighbor)) {
                                queue.add(neighbor);
                            }
                        }
                    }
                }
                if (current.clusterId == DBSCAN_UNCLASSIFIED || current.clusterId == DBSCAN_NOISE) {
                    current.clusterId = clusterId;
                }
            }
        }
    }

    private List<GeoPoiPoint> regionQuery(List<GeoPoiPoint> points, GeoPoiPoint center, double epsMeters) {
        List<GeoPoiPoint> neighbors = new ArrayList<>();
        for (GeoPoiPoint candidate : points) {
            double meters = haversineMeters(center.latitude, center.longitude, candidate.latitude, candidate.longitude);
            if (meters <= epsMeters) {
                neighbors.add(candidate);
            }
        }
        return neighbors;
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        // Haversine公式：适合经纬度球面距离计算，结果单位米。
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double sinLat = Math.sin(dLat / 2D);
        double sinLon = Math.sin(dLon / 2D);
        double a = sinLat * sinLat + Math.cos(radLat1) * Math.cos(radLat2) * sinLon * sinLon;
        double c = 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
        return EARTH_RADIUS_METERS * c;
    }

    private double[] parseLonLat(String location) {
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            double lon = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            if (!Double.isFinite(lon)
                    || !Double.isFinite(lat)
                    || lon < -180D
                    || lon > 180D
                    || lat < -90D
                    || lat > 90D) {
                return null;
            }
            return new double[]{lon, lat};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static class GeoPoiPoint {
        private final double longitude;
        private final double latitude;
        private final GaoDeDTO.ClusterPoi poi;
        private boolean visited;
        private int clusterId = DBSCAN_UNCLASSIFIED;

        private GeoPoiPoint(double longitude, double latitude, GaoDeDTO.ClusterPoi poi) {
            this.longitude = longitude;
            this.latitude = latitude;
            this.poi = poi;
        }
    }
}
