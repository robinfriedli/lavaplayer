package com.sedmelluq.discord.lavaplayer.source.youtube;

import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.ContentType;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.sedmelluq.discord.lavaplayer.tools.DataFormatTools.convertToMapLayout;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

public class DefaultYoutubeTrackDetails implements YoutubeTrackDetails {
  private static final Logger log = LoggerFactory.getLogger(DefaultYoutubeTrackDetails.class);

  private static final String DEFAULT_SIGNATURE_KEY = "signature";

  private final String videoId;
  private final JsonBrowser info;

  public DefaultYoutubeTrackDetails(String videoId, JsonBrowser info) {
    this.videoId = videoId;
    this.info = info;
  }

  @Override
  public AudioTrackInfo getTrackInfo() {
    try {
      return loadTrackInfo();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public List<YoutubeTrackFormat> getFormats(HttpInterface httpInterface, YoutubeSignatureResolver signatureResolver) {
    try {
      return loadTrackFormats(httpInterface, signatureResolver);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String getPlayerScript() {
    return info.get("assets").get("js").text();
  }

  private List<YoutubeTrackFormat> loadTrackFormats(
      HttpInterface httpInterface,
      YoutubeSignatureResolver signatureResolver
  ) throws Exception {
    JsonBrowser args = info.safeGet("args");

    String adaptiveFormats = args.safeGet("adaptive_fmts").text();
    if (adaptiveFormats != null) {
      return loadTrackFormatsFromAdaptive(adaptiveFormats);
    }

    String playerResponse = args.safeGet("player_response").text();

    if (playerResponse != null) {
      JsonBrowser streamingData = JsonBrowser.parse(playerResponse)
          .safeGet("streamingData");

      if (!streamingData.isNull()) {
        List<YoutubeTrackFormat> formats = loadTrackFormatsFromStreamingData(streamingData.safeGet("formats"));
        formats.addAll(loadTrackFormatsFromStreamingData(streamingData.safeGet("adaptiveFormats")));

        if (!formats.isEmpty()) {
          return formats;
        }
      }
    }

    String dashUrl = args.safeGet("dashmpd").text();
    if (dashUrl != null) {
      return loadTrackFormatsFromDash(dashUrl, httpInterface, signatureResolver);
    }

    String formatStreamMap = args.safeGet("url_encoded_fmt_stream_map").text();
    if (formatStreamMap != null) {
      return loadTrackFormatsFromFormatStreamMap(formatStreamMap);
    }

    log.warn("Video {} with no detected format field, arguments are: {}", videoId, args.format());

    throw new FriendlyException("Unable to play this YouTube track.", SUSPICIOUS,
        new IllegalStateException("No adaptive formats, no dash, no stream map."));
  }

  private List<YoutubeTrackFormat> loadTrackFormatsFromAdaptive(String adaptiveFormats) throws Exception {
    List<YoutubeTrackFormat> tracks = new ArrayList<>();

    for (String formatString : adaptiveFormats.split(",")) {
      Map<String, String> format = decodeUrlEncodedItems(formatString, false);

      tracks.add(new YoutubeTrackFormat(
          ContentType.parse(format.get("type")),
          Long.parseLong(format.get("bitrate")),
          Long.parseLong(format.get("clen")),
          format.get("url"),
          format.get("s"),
          format.getOrDefault("sp", DEFAULT_SIGNATURE_KEY)
      ));
    }

    return tracks;
  }

  private List<YoutubeTrackFormat> loadTrackFormatsFromFormatStreamMap(String adaptiveFormats) throws Exception {
    List<YoutubeTrackFormat> tracks = new ArrayList<>();
    boolean anyFailures = false;

    for (String formatString : adaptiveFormats.split(",")) {
      try {
        Map<String, String> format = decodeUrlEncodedItems(formatString, false);
        String url = format.get("url");

        if (url == null) {
          continue;
        }

        String contentLength = DataFormatTools.extractBetween(url, "clen=", "&");

        if (contentLength == null) {
          log.debug("Could not find content length from URL {}, skipping format", url);
          continue;
        }

        tracks.add(new YoutubeTrackFormat(
            ContentType.parse(format.get("type")),
            qualityToBitrateValue(format.get("quality")),
            Long.parseLong(contentLength),
            url,
            format.get("s"),
            format.getOrDefault("sp", DEFAULT_SIGNATURE_KEY)
        ));
      } catch (RuntimeException e) {
        anyFailures = true;
        log.debug("Failed to parse format {}, skipping.", formatString, e);
      }
    }

    if (tracks.isEmpty() && anyFailures) {
      log.warn("In adaptive format map {}, all formats either failed to load or were skipped due to missing fields",
          adaptiveFormats);
    }

    return tracks;
  }

  private List<YoutubeTrackFormat> loadTrackFormatsFromStreamingData(JsonBrowser formats) {
    List<YoutubeTrackFormat> tracks = new ArrayList<>();
    boolean anyFailures = false;

    if (!formats.isNull() && formats.isList()) {
      for (JsonBrowser formatJson : formats.values()) {
        String cipher = formatJson.safeGet("cipher").text();
        Map<String, String> cipherInfo = cipher != null
            ? decodeUrlEncodedItems(cipher, true)
            : Collections.emptyMap();

        try {
          JsonBrowser contentLength = formatJson.safeGet("contentLength");

          if (contentLength.isNull()) {
            log.debug("Could not find content length from streamingData format {}, skipping", formatJson.format());
            continue;
          }

          tracks.add(new YoutubeTrackFormat(
              ContentType.parse(formatJson.safeGet("mimeType").text()),
              formatJson.safeGet("bitrate").as(Long.class),
              contentLength.as(Long.class),
              cipherInfo.getOrDefault("url", formatJson.get("url").text()),
              cipherInfo.get("s"),
              cipherInfo.getOrDefault("sp", DEFAULT_SIGNATURE_KEY)
          ));
        } catch (RuntimeException e) {
          anyFailures = true;
          log.debug("Failed to parse format {}, skipping", formatJson, e);
        }
      }
    }

    if (tracks.isEmpty() && anyFailures) {
      log.warn("In streamingData adaptive formats {}, all formats either failed to load or were skipped due to missing " +
          "fields", formats.format());
    }

    return tracks;
  }

  private long qualityToBitrateValue(String quality) {
    // Return negative bitrate values to indicate missing bitrate info, but still retain the relative order.
    if ("small".equals(quality)) {
      return -10;
    } else if ("medium".equals(quality)) {
      return -5;
    } else if ("hd720".equals(quality)) {
      return -4;
    } else {
      return -1;
    }
  }

  private List<YoutubeTrackFormat> loadTrackFormatsFromDash(
      String dashUrl,
      HttpInterface httpInterface,
      YoutubeSignatureResolver signatureResolver
  ) throws Exception {
    String resolvedDashUrl = signatureResolver.resolveDashUrl(httpInterface, getPlayerScript(), dashUrl);

    try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(resolvedDashUrl))) {
      int statusCode = response.getStatusLine().getStatusCode();
      if (statusCode != 200) {
        throw new IOException("Invalid status code for track info page response: " + statusCode);
      }

      Document document = Jsoup.parse(response.getEntity().getContent(), StandardCharsets.UTF_8.name(), "",
          Parser.xmlParser());
      return loadTrackFormatsFromDashDocument(document);
    }
  }

  private List<YoutubeTrackFormat> loadTrackFormatsFromDashDocument(Document document) {
    List<YoutubeTrackFormat> tracks = new ArrayList<>();

    for (Element adaptation : document.select("AdaptationSet")) {
      String mimeType = adaptation.attr("mimeType");

      for (Element representation : adaptation.select("Representation")) {
        String url = representation.select("BaseURL").first().text();
        String contentLength = DataFormatTools.extractBetween(url, "/clen/", "/");
        String contentType = mimeType + "; codecs=" + representation.attr("codecs");

        if (contentLength == null) {
          log.debug("Skipping format {} because the content length is missing", contentType);
          continue;
        }

        tracks.add(new YoutubeTrackFormat(
            ContentType.parse(contentType),
            Long.parseLong(representation.attr("bandwidth")),
            Long.parseLong(contentLength),
            url,
            null,
            DEFAULT_SIGNATURE_KEY
        ));
      }
    }

    return tracks;
  }

  private AudioTrackInfo loadTrackInfo() throws IOException {
    if (info == null) {
      return null;
    }

    JsonBrowser args = info.get("args");
    boolean useOldFormat = args.get("player_response").isNull();

    if (useOldFormat) {
      if ("fail".equals(args.get("status").text())) {
        throw new FriendlyException(args.get("reason").text(), COMMON, null);
      }

      boolean isStream = "1".equals(args.get("live_playback").text());
      long duration = isStream ? Long.MAX_VALUE : args.get("length_seconds").as(Long.class) * 1000;
      return buildTrackInfo(videoId, args.get("title").text(), args.get("author").text(), isStream, duration);
    }

    JsonBrowser playerResponse = JsonBrowser.parse(args.get("player_response").text());
    JsonBrowser playabilityStatus = playerResponse.get("playabilityStatus");

    if ("ERROR".equals(playabilityStatus.get("status").text())) {
      throw new FriendlyException(playabilityStatus.get("reason").text(), COMMON, null);
    }

    JsonBrowser videoDetails = playerResponse.get("videoDetails");

    boolean isStream = videoDetails.get("isLiveContent").as(Boolean.class);
    long duration = isStream ? Long.MAX_VALUE : videoDetails.get("lengthSeconds").as(Long.class) * 1000;

    return buildTrackInfo(videoId, videoDetails.get("title").text(), videoDetails.get("author").text(), isStream, duration);
  }

  private AudioTrackInfo buildTrackInfo(String videoId, String title, String uploader, boolean isStream, long duration) {
    return new AudioTrackInfo(title, uploader, duration, videoId, isStream,
        "https://www.youtube.com/watch?v=" + videoId);
  }

  private static Map<String, String> decodeUrlEncodedItems(String input, boolean escapedSeparator) {
    if (escapedSeparator) {
      input = input.replace("\\\\u0026", "&");
    }

    return convertToMapLayout(URLEncodedUtils.parse(input, StandardCharsets.UTF_8));
  }
}
