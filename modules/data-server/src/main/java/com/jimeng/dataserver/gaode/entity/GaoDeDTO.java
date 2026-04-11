package com.jimeng.dataserver.gaode.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public interface GaoDeDTO {

    @Data
    @Builder(builderClassName = "Builder", builderMethodName = "newBuilder", setterPrefix = "set")
    @NoArgsConstructor
    @AllArgsConstructor
    class KeyworkPOI {
        // 地点关键字
        String keywords;
        // 指定地点类型
        String types;
        // 搜索区划
        String region;
        // 指定城市数据召回限制，默认true
        @lombok.Builder.Default
        Boolean cityLimit = Boolean.TRUE;
        // 返回子POI和商圈
        @lombok.Builder.Default
        String showFields = "children,business";
        @lombok.Builder.Default
        int pageSize = 25;
        @lombok.Builder.Default
        int pageNum = 1;
    }

    @Data
    @Builder(builderClassName = "Builder", builderMethodName = "newBuilder", setterPrefix = "set")
    @NoArgsConstructor
    @AllArgsConstructor
    class AroundPOI {
        // 地点关键字
        String keywords;
        // 指定地点类型
        String types;
        // 搜索区划
        String region;
        // 中心点坐标
        String location;
        // 搜索半径
        Long radius = 1000L;
        // 排序规则，weight / distance
        @lombok.Builder.Default
        String sortRule = "weight";
        // 指定城市数据召回限制，默认true
        @lombok.Builder.Default
        Boolean cityLimit = Boolean.TRUE;
        // 返回子POI和商圈
        @lombok.Builder.Default
        String showFields = "children,business";
        @lombok.Builder.Default
        int pageSize = 25;
        @lombok.Builder.Default
        int pageNum = 1;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    class KeywordPOIResp {
        private String status;
        private String info;
        private String infocode;
        private String count;
        private List<POI> pois;
        private Suggestion suggestion;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    class AroundPOIResp {
        private String status;
        private String info;
        private String infocode;
        private String count;
        private List<POI> pois;
        private Object description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    class POI {
        private String id;
        private String parent;
        private String shopid;
        private String name;
        private String type;
        private String typecode;
        private String address;
        private String location;
        private String distance;
        private String pname;
        private String cityname;
        private String adname;
        private String pcode;
        private String citycode;
        private String adcode;
        private List<POIChild> children;
        private POIBusiness business;
        private POIIndoor indoor;
        private POINavi navi;
        private List<POIPhoto> photos;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    class POIChild {
        private String id;
        private String name;
        private String location;
        private String address;
        private String subtype;
        private String typecode;
        private String sname;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    class POIBusiness {
        @JsonProperty("business_area")
        private String businessArea;
        @JsonProperty("opentime_today")
        private String opentimeToday;
        @JsonProperty("opentime_week")
        private String opentimeWeek;
        private String tel;
        private String tag;
        private String rating;
        private String cost;
        @JsonProperty("parking_type")
        private String parkingType;
        private String alias;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    class POIIndoor {
        @JsonProperty("indoor_map")
        private String indoorMap;
        private String cpid;
        private String floor;
        private String truefloor;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    class POINavi {
        @JsonProperty("navi_poiid")
        private String naviPoiid;
        @JsonProperty("entr_location")
        private String entrLocation;
        @JsonProperty("exit_location")
        private String exitLocation;
        private String gridcode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    class POIPhoto {
        private String title;
        private String url;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    class Suggestion {
        private List<String> keywords;
        private List<SuggestionCity> cities;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    class SuggestionCity {
        private String name;
        private String citycode;
        private String adcode;
        private String num;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class PoiClusterResp {
        // 原始POI集个数
        private Integer totalPoiCount;
        // 可用经纬度的POI个数
        private Integer validPoiCount;
        // 簇个数
        private Integer clusterCount;
        // 噪声点个数
        private Integer noiseCount;
        // 簇集合
        private List<PoiCluster> clusters;
        // 噪声集合
        private List<ClusterPoi> noisePois;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class PoiClusterByTypecodeResp {
        // 原始POI总数
        private Integer totalPoiCount;
        // typecode分类数量
        private Integer typecodeCategoryCount;
        // 按typecode分类后的聚类结果
        private List<TypecodeClusterResult> typecodeClusters;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class TypecodeClusterResult {
        private String typecode;
        private PoiClusterResp clusterResult;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class PoiCluster {
        private Integer clusterId;
        private String centerLocation;
        private Integer poiCount;
        private List<ClusterPoi> pois;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ClusterPoi {
        private String location;
        private String type;
        private String typecode;
        private String address;
        private List<ClusterSubPoi> children;
        private POIBusiness business;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ClusterSubPoi {
        private String location;
        private String address;
        private String subtype;
        private String typecode;
        private String sname;
    }

}
