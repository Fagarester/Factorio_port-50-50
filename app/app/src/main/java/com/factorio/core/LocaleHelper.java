package com.factorio.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;

import androidx.preference.PreferenceManager;

import java.util.Locale;

public class LocaleHelper {
    private static final String[] supportedLocales = {
        "en_US", "zh_CN", "es_ES", "hi_IN", "ar_SA", 
        "fr_FR", "bn_BD", "pt_BR", "ru_RU", "ja_JP", 
        "be_BY", "de_DE", "jv_ID", "ko_KR", "kk_KZ", 
        "vi_VN", "mr_IN", "ta_IN", "tr_TR", "ur_PK", 
        "it_IT", "th_TH", "gu_IN", "kn_IN", "ga_IE", 
        "uk_UA", "pl_PL", "ro_RO", "nl_NL", "hu_HU"
    };

    public static int getLocaleIndex(Context context) {
        Configuration configuration = context.getResources().getConfiguration();
        LocaleList localeList = configuration.getLocales();
        String locale = !localeList.isEmpty() ? localeList.get(0).toString() : "";

        for (int i = 0; i < supportedLocales.length; i++) {
            if (locale.startsWith(supportedLocales[i].substring(0, 2))) return i;
        }
        return 0;
    }

    public static Context setSystemLocale(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        int index = preferences.getInt("lc_index", -1);

        if (index < 0 || index >= supportedLocales.length) return context;
        String[] parts = supportedLocales[index].split("_");
        Locale locale = new Locale(parts[0], parts[1]);
        Locale.setDefault(locale);

        Configuration configuration = context.getResources().getConfiguration();
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return context.createConfigurationContext(configuration);
    }

    public static void setEnvVars(EnvVars envVars) {
        Locale locale = Locale.getDefault();
        for (String name : supportedLocales) {
            if (locale.toString().startsWith(name.substring(0, 2))) {
                envVars.put("LC_ALL", name + ".UTF-8");
                return;
            }
        }

        envVars.put("LC_ALL", "en_US.UTF-8");
    }
}

