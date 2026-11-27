package net.gorry.mase;

/**
 * アプリ名・バージョンなどの定数。値はビルドのときに Profile.ini から取り込む（app/build.gradle）。
 * コードやドキュメントに、これらの値を直接書かない。
 */
final class AppProfile {
    static final String TITLE = BuildConfig.PROFILE_TITLE;
    static final String SHORT_TITLE = BuildConfig.PROFILE_SHORT_TITLE;
    static final String VERSION = BuildConfig.PROFILE_VERSION;
    static final int VERSION_NUMBER = BuildConfig.PROFILE_VERSION_NUMBER;
    static final String COPYRIGHT = BuildConfig.PROFILE_COPYRIGHT;
    static final String AUTHOR = BuildConfig.PROFILE_AUTHOR;
    static final String PROJECT_URL = BuildConfig.PROFILE_PROJECT_URL;
    static final String RELEASE_URL = BuildConfig.PROFILE_RELEASE_URL;
    static final String NOTICE_URL = BuildConfig.PROFILE_NOTICE_URL;
    /** 操作マニュアルの URL（メニューの「操作マニュアル」で開く）。なければ空 */
    static final String HELP_URL = BuildConfig.PROFILE_HELP_URL;

    private AppProfile() {
    }
}
