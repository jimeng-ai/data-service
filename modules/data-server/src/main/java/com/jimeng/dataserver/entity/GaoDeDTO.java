package com.jimeng.dataserver.entity;

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
        // 指定地点类型，默认
        String types;
        // 搜索区划
        String region;
        // 指定城市数据召回限制，默认true
        Boolean cityLimit = Boolean.TRUE;
        // 返回子POI和商圈
        String showFields = "children,business";
        int pageSize = 25;
        int pageNum = 1;
    }

    @Data
    @Builder(builderClassName = "Builder", builderMethodName = "newBuilder", setterPrefix = "set")
    @NoArgsConstructor
    @AllArgsConstructor
    class AroundPOI {
        // 地点关键字
        String keywords;
        // 指定地点类型，默认
        String types;
        // 搜索区划
        String region;
        // 中心点坐标
        String location;
        // 搜索半径
        int radius;
        // 排序规则
        String sortRule;
        // 指定城市数据召回限制，默认true
        Boolean cityLimit = Boolean.TRUE;
        // 返回子POI和商圈
        String showFields = "children,business";
        int pageSize = 25;
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

}
