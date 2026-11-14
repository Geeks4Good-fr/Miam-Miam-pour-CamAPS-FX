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

# Ligne utile dans les rapports de plantage, sans révéler de données de santé.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
