package no.nixo.bluecodec;

import java.util.Locale;

final class CodecDescriptions {
  private CodecDescriptions() {}

  static String forName(String name) {
    String normalized = name.toLowerCase(Locale.ROOT).replace("-", " ");
    if (normalized.equals("aptx hd")) return "24-bit Bluetooth audio at up to 576 kbps.";
    if (normalized.equals("ldac"))
      return "Sony’s codec, with bitrates up to 990 kbps. Bitrate can adjust to connection"
                 + " conditions.";
    if (normalized.equals("aptx")) return "Qualcomm’s standard aptX codec for Bluetooth audio.";
    if (normalized.equals("aac"))
      return "Advanced Audio Coding. Uses efficient compression for Bluetooth audio.";
    if (normalized.equals("sbc"))
      return "Standard Bluetooth audio codec with broad device compatibility.";
    return "An audio codec supported by this device. Android selects the connection settings.";
  }
}
