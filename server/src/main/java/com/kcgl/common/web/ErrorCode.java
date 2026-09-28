package com.kcgl.common.web;

/**
 * 全局错误码（docs/01 六节统一响应契约 {code,message,data}）。
 * M1 i18n 切片将把 message 换成 message key，code 数字是对外稳定契约不再变更。
 */
public enum ErrorCode {

    OK(0, "成功"),

    VALIDATION(400001, "入力内容に誤りがあります"),
    OLD_PASSWORD_MISMATCH(400101, "現在のパスワードが正しくありません"),
    PASSWORD_POLICY(400102, "新しいパスワードが条件を満たしていません"),

    UNAUTHENTICATED(401001, "ログインが必要です"),
    BAD_CREDENTIALS(401002, "ユーザー名またはパスワードが正しくありません"),
    ACCOUNT_DISABLED(401003, "このアカウントは無効になっています"),

    FORBIDDEN(403001, "この操作を行う権限がありません"),
    BAD_ORIGIN(403002, "許可されていないアクセス元です"),

    NOT_FOUND(404001, "対象が見つかりません"),
    PRICE_BAND_NOT_MATCHED(404002, "この価格に該当する価格帯がありません。管理画面で価格帯を設定してください"),
    VENUE_NOT_FOUND(404003, "選択された会場が存在しません"),
    YEAR_CODE_NOT_FOUND(404004, "落札日の年に対応する年代号が未登録です。管理画面で年代号を登録してください"),

    IMAGE_FORMAT_INVALID(400005, "対応していない画像形式です（JPEG / PNG のみ）"),
    IMAGE_TOO_LARGE(400006, "画像サイズが上限（5MB）を超えています"),
    IMAGE_PIXEL_LIMIT(400007, "画像の解像度が上限（8000×8000）を超えています"),
    IMAGE_COUNT_LIMIT(400008, "商品画像は1件につき9枚までです"),

    YAHOO_FILE_TOO_LARGE(400009, "CSVファイルは50MB以内にしてください"),
    YAHOO_FILE_EMPTY(400010, "CSVファイルが空です"),
    YAHOO_ROW_LIMIT(400011, "CSVの行数が上限（20万行）を超えています"),
    YAHOO_ENCODING_UNDETECTABLE(400012, "CSVファイルの文字コードを判定できませんでした"),

    ACCOUNT_LOCKED(423001, "アカウントがロックされました。しばらくしてからもう一度お試しください"),

    USER_EXISTS(409001, "同じユーザー名が既に存在します"),
    VENUE_EXISTS(409002, "この会場コードは既に登録されています"),
    PRICE_BAND_EXISTS(409003, "この価格帯コードは既に登録されています"),
    PRICE_BAND_OVERLAP(409004, "価格帯の範囲が既存の価格帯と重複しています"),
    YEAR_CODE_EXISTS(409005, "この年または年代号は既に登録されています"),
    ITEM_ALREADY_VOIDED(409006, "この商品は既に取り消されています"),
    ITEM_NOT_VOIDED(409007, "再登録元の商品が取り消されていません。先に取り消してください"),
    INVALID_TRANSITION(409008, "商品の現在の状態ではこの操作はできません。画面を再読み込みして確認してください"),
    STOCKTAKE_ACTIVE_EXISTS(409009, "この倉庫では実行中の棚卸があります。既存の棚卸を続けてください"),
    STOCKTAKE_STATUS_INVALID(409010, "この棚卸は現在の状態では操作できません。画面を再読み込みして確認してください"),
    YAHOO_BATCH_DUPLICATE(409011, "同じ内容のCSVファイルは既にインポート済みです"),
    /** 乐观锁 version 冲突（通用：对象在操作窗口内被他人变更）。 */
    CONFLICT(409000, "操作対象が更新されています。画面を再読み込みしてもう一度お試しください"),

    RATE_LIMITED(429001, "送信回数が上限を超えました。しばらくしてからもう一度お試しください"),

    INTERNAL(500000, "システムエラーが発生しました");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
