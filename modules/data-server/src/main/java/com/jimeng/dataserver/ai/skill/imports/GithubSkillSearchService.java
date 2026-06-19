package com.jimeng.dataserver.ai.skill.imports;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GithubSkillSearchService {
    private static final List<String> CURATED_OWNERS = List.of("anthropics");
    private final SkillImportProperties props;

    public List<SkillCandidate> search(String keyword) {
        String q = URLEncoder.encode("filename:SKILL.md " + (keyword == null ? "" : keyword), StandardCharsets.UTF_8);
        String url = "https://api.github.com/search/code?per_page=10&q=" + q;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(props.getFetchTimeoutSec()))
                    .proxy(java.net.ProxySelector.getDefault())
                    .build();
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getFetchTimeoutSec()))
                    .header("Accept", "application/vnd.github+json").GET();
            if (props.getGithubToken() != null && !props.getGithubToken().isBlank())
                req.header("Authorization", "Bearer " + props.getGithubToken());
            HttpResponse<String> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("github code search HTTP {}", resp.statusCode());
                return List.of();
            }
            return rank(parse(resp.body()), CURATED_OWNERS);
        } catch (Exception e) {
            log.warn("github code search 异常: {}", e.getMessage());
            return List.of();
        }
    }

    static List<SkillCandidate> parse(String body) {
        List<SkillCandidate> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;
        JSONObject root = JSONUtil.parseObj(body);
        JSONArray items = root.getJSONArray("items");
        if (items == null) return out;
        for (Object o : items) {
            JSONObject it = (JSONObject) o;
            JSONObject repo = it.getJSONObject("repository");
            if (repo == null) continue;
            JSONObject ownerObj = repo.getJSONObject("owner");
            SkillCandidate c = new SkillCandidate();
            c.setOwner(ownerObj == null ? null : ownerObj.getStr("login"));
            c.setRepo(repo.getStr("name"));
            String defaultBranch = repo.getStr("default_branch");
            c.setRef(defaultBranch != null ? defaultBranch : "main");
            c.setPath(dirOf(it.getStr("path")));
            c.setDescription(repo.getStr("description"));
            c.setStars(repo.getInt("stargazers_count"));
            out.add(c);
        }
        return out;
    }

    static String dirOf(String skillMdPath) {
        if (skillMdPath == null) return "";
        int idx = skillMdPath.lastIndexOf('/');
        return idx < 0 ? "" : skillMdPath.substring(0, idx);
    }

    static List<SkillCandidate> rank(List<SkillCandidate> in, List<String> curatedOwners) {
        List<SkillCandidate> list = new ArrayList<>(in);
        for (SkillCandidate c : list) c.setCurated(c.getOwner() != null && curatedOwners.contains(c.getOwner()));
        list.sort(Comparator
                .comparing(SkillCandidate::isCurated, Comparator.reverseOrder())
                .thenComparing(c -> c.getStars() == null ? 0 : c.getStars(), Comparator.reverseOrder()));
        return list;
    }
}
