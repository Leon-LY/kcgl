package com.kcgl.module.yahoo;

import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import org.mozilla.universalchardet.UniversalDetector;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.List;
import java.util.Locale;

/**
 * CSV 编码检测链（docs/01 7.4 唯一定义）：UTF-8 BOM → chardet 提示序 → 候选链
 * 严格解码验证（REPORT 拒绝替换字符），全败抛错（解码发生在异步批次内→批次失败，
 * 非上传时 400）。SHIFT_JIS/Windows-31J 一律按 MS932 处理：NEC/IBM 扩展字
 * （①㈱℡）在纯 Shift_JIS 语义下无映射，MS932 是其实际超集。
 */
public final class YahooCsvDecoder {

    /** 解码结果：文本 + 判定的编码名（落 batch.encoding_detected）。 */
    public record Decoded(String text, String encoding) {
    }

    private YahooCsvDecoder() {
    }

    public static Decoded decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            Decoded decoded = tryStrictDecode(bytes, 3, "UTF-8");
            if (decoded != null) {
                return decoded; // BOM 即铁证
            }
        }
        for (String charset : candidateChain(detectCharsetHint(bytes))) {
            Decoded decoded = tryStrictDecode(bytes, 0, charset);
            if (decoded != null) {
                return decoded;
            }
        }
        throw new BizException(ErrorCode.YAHOO_ENCODING_UNDETECTABLE);
    }

    /** chardet 实例式探测：采样前缀（检测器在少量数据内即收敛，全量大文件无谓）。 */
    private static String detectCharsetHint(byte[] bytes) {
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(bytes, 0, Math.min(bytes.length, 16_384));
        detector.dataEnd();
        String hint = detector.getDetectedCharset();
        detector.reset();
        return hint;
    }

    /** 候选链：chardet 提示优先（Shift_JIS 家族统一转 MS932），未知/ASCII 按 UTF-8 起。 */
    private static List<String> candidateChain(String hint) {
        String normalized = hint == null ? "" : hint.toUpperCase(Locale.ROOT);
        if (normalized.equals("SHIFT_JIS") || normalized.equals("WINDOWS-31J")
                || normalized.contains("SJIS")) {
            return List.of("MS932", "UTF-8", "EUC-JP");
        }
        if (normalized.equals("EUC-JP")) {
            return List.of("EUC-JP", "UTF-8", "MS932");
        }
        return List.of("UTF-8", "MS932", "EUC-JP");
    }

    /** 严格解码（malformed/unmappable 一律失败而非替换字符）；失败返回 null 走下一候选。 */
    private static Decoded tryStrictDecode(byte[] bytes, int offset, String charsetName) {
        try {
            CharsetDecoder decoder = Charset.forName(charsetName).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String text = decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            return new Decoded(text, charsetName);
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
