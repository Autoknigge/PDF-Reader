# PdfBox-Android: Verschlüsselung wird per Reflexion geladen, optionale Bibliotheken fehlen absichtlich.
-keep class com.tom_roush.pdfbox.pdmodel.encryption.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
