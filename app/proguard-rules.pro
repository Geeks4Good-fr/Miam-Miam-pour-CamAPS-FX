# Règles R8/ProGuard de Meal Announcer.
#
# Hilt, Room, Compose et (plus tard) Retrofit fournissent leurs propres règles via
# « consumer rules » : on n'ajoute ici que ce qui concerne notre code.

# Le modèle de domaine sert à écrire le payloadJson du journal, et sera relu plus tard :
# ses noms de champs doivent rester stables.
-keepclassmembers class com.najah.mealannouncer.core.model.** {
    <fields>;
}

# Les entités Room sont instanciées par réflexion générée : on garde leurs constructeurs.
-keep class com.najah.mealannouncer.core.database.entity.** { <init>(...); }

# sherpa-onnx : le code natif lit les champs des configurations Kotlin par leur nom (JNI).
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Vosk (mot-clé « Miam-miam ») passe par JNA : le code natif de JNA retrouve ses classes et
# leurs champs PAR LEUR NOM (Pointer.peer, Structure, Callback…). Sans ces règles, R8 les
# renomme et la version publiée plante dès que l'écoute du mot-clé démarre
# (« Can't obtain peer field ID for class com.sun.jna.Pointer », corrigé en 0.15.2).
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Ligne utile dans les rapports de plantage, sans révéler de données de santé.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
