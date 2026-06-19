package com.jimeng.dataserver.ai.skill.imports;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubTarballFetcher {
    private static final Pattern SEG = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private final SkillImportProperties props;

    public static String buildUrl(String owner, String repo, String ref) {
        for (String s : new String[]{owner, repo, ref}) {
            if (s == null || !SEG.matcher(s).matches())
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "非法 GitHub 坐标: " + s);
        }
        return "https://codeload.github.com/" + owner + "/" + repo + "/tar.gz/" + ref;
    }

    public byte[] fetch(String owner, String repo, String ref) {
        String url = buildUrl(owner, repo, ref);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(props.getFetchTimeoutSec()))
                    .proxy(java.net.ProxySelector.getDefault())
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getFetchTimeoutSec())).GET();
            if (props.getGithubToken() != null && !props.getGithubToken().isBlank())
                req.header("Authorization", "Bearer " + props.getGithubToken());
            HttpResponse<byte[]> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                        "拉取 GitHub 失败 HTTP " + resp.statusCode() + "（确认 owner/repo/ref 正确且仓库公开）");
            byte[] body = resp.body();
            if (body.length > props.getMaxTotalBytes() * 4)
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "下载体积过大");
            return body;
        } catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException(ExceptionCode.INVALID_REQUEST, "拉取 GitHub 异常: " + e.getMessage()); }
    }
}
