package com.najah.mealannouncer.data.openfoodfacts

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.FoodSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La base Open Food Facts complète, copiée sur le téléphone.
 *
 * Chercher sur Internet à chaque aliment est le défaut de départ : c'est lent, ça ne marche
 * pas en avion, et cela envoie à un service extérieur ce qu'on s'apprête à manger. Le fichier
 * livré ici contient tous les produits d'Open Food Facts qui portent une valeur de glucides
 * (plus de trois millions) avec un index plein texte sur leur nom : la recherche est locale,
 * immédiate, et Internet ne sert plus que pour ce qui n'y est vraiment pas.
 *
 * Le fichier est en lecture seule et vit dans le dossier de l'application
 * (`Android/data/<paquet>/files/off-base.db`). Il est produit par
 * `<dossier>\CraftMCP\off\construire_base.py` à partir de l'export public
 * d'Open Food Facts (licence ODbL) et copié avec `adb push`.
 */
@Singleton
class OffDatabase @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val context = context.applicationContext

    @Volatile
    private var database: SQLiteDatabase? = null

    @Volatile
    private var unavailable = false

    /** Vrai si la base installée porte les colonnes de portion : découvert à l'ouverture. */
    @Volatile
    private var avecPortion = false

    /** Emplacement attendu du fichier : le dossier externe de l'application, sinon l'interne. */
    fun file(): File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        FILE_NAME,
    )

    /** Vrai si la base est installée sur l'appareil. */
    val available: Boolean get() = file().isFile

    /** Nombre de produits de la base, ou `null` si elle n'est pas installée. */
    suspend fun productCount(): Int? = withContext(Dispatchers.IO) {
        val base = open() ?: return@withContext null
        try {
            base.rawQuery("SELECT COUNT(*) FROM produits", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else null
            }
        } catch (error: SQLiteException) {
            fail(error)
            null
        }
    }

    /**
     * Cherche les produits dont le nom contient les mots de [query].
     *
     * La recherche est volontairement stricte (tous les mots, en préfixe) : c'est
     * l'appelant qui juge ensuite si le nom trouvé correspond vraiment à l'aliment demandé.
     * Si la phrase est trop précise pour la base, [OffText.matchAttempts] fournit des essais
     * de plus en plus larges, si bien qu'un aliment connu ne part pas sur Internet pour un
     * adjectif manquant.
     */
    suspend fun search(
        query: String,
        limit: Int = 20,
        preferredBrands: List<String> = emptyList(),
    ): List<FoodProduct> = withContext(Dispatchers.IO) {
        val essais = OffText.matchAttempts(query)
        if (essais.isEmpty()) return@withContext emptyList()
        val base = open() ?: return@withContext emptyList()
        val borne = limit.coerceIn(1, 200)
        for (essai in essais) {
            val lignes = queryProducts(base, essai, query, borne, preferredBrands)
            if (lignes.isNotEmpty()) return@withContext lignes
        }
        emptyList()
    }

    /** Retrouve un produit par son code-barres, sans réseau. */
    suspend fun findByBarcode(barcode: String): FoodProduct? = withContext(Dispatchers.IO) {
        val code = barcode.trim()
        if (code.isEmpty()) return@withContext null
        val base = open() ?: return@withContext null
        try {
            base.rawQuery(
                "SELECT ${colonnes()} FROM produits p WHERE p.code = ? LIMIT 1",
                arrayOf(code),
            ).use { cursor ->
                if (cursor.moveToFirst()) product(cursor) else null
            }
        } catch (error: SQLiteException) {
            fail(error)
            null
        }
    }

    private fun queryProducts(
        base: SQLiteDatabase,
        match: String,
        query: String,
        limit: Int,
        preferredBrands: List<String> = emptyList(),
    ): List<FoodProduct> = try {
        // L'index est rempli dans l'ordre des produits les plus scannés : on ne lit donc que
        // les [SCAN_LIMIT] premières correspondances, qui sont les plus connues, avant de les
        // départager. Trier toutes les correspondances coûtait 17 s sur « chocolat » (141 000
        // lignes) ; borner l'index ramène la recherche à quelques dizaines de millisecondes.
        // Quand la requête contient aussi un nom de marque (« Carrefour Classic 8 pains au
        // chocolat »), l'index plein texte ne porte que sur le nom du produit : la colonne
        // `marque` départage alors les candidats de même nom générique.
        val sql = "SELECT ${colonnes()} FROM " +
            "(SELECT code FROM recherche WHERE nom_norm MATCH ? LIMIT $SCAN_LIMIT) m " +
            "JOIN produits p ON p.code = m.code " +
            "ORDER BY (p.nom_norm = ?) DESC, " +
            "CASE WHEN p.marque IS NOT NULL AND p.marque != '' " +
            "AND ? LIKE '%' || lower(p.marque) || '%' THEN 1 ELSE 0 END DESC, " +
            "p.scans DESC LIMIT ?"
        val prefix = OffText.normalize(query)
        val poolLimit = if (preferredBrands.isEmpty()) limit else maxOf(limit * 4, 40).coerceAtMost(200)
        val rows = base.rawQuery(sql, arrayOf(match, prefix, " $prefix ", poolLimit.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(product(cursor))
            }
        }
        rankByPreferredBrands(rows, prefix, preferredBrands).take(limit)
    } catch (error: SQLiteException) {
        fail(error)
        emptyList()
    }

    /**
     * Si l'utilisateur a choisi ses enseignes habituelles (Carrefour, E.Leclerc, Système U…) et
     * qu'aucune marque explicite n'était déjà citée dans [normalizedQuery], les produits issus
     * de ces enseignes passent en tête à égalité de correspondance.
     */
    internal fun rankByPreferredBrands(
        products: List<FoodProduct>,
        normalizedQuery: String,
        preferredBrands: List<String>,
    ): List<FoodProduct> {
        if (preferredBrands.isEmpty() || products.size <= 1) return products
        val normKeywords = preferredBrands.map { OffText.normalize(it) }.filter { it.isNotEmpty() }
        if (normKeywords.isEmpty()) return products
        val explicitBrandInQuery = products.any { prod ->
            val b = prod.brand?.let { OffText.normalize(it) }.orEmpty()
            b.length >= 2 && normalizedQuery.contains(b)
        }
        if (explicitBrandInQuery) return products
        return products.sortedByDescending { prod ->
            val brandNorm = prod.brand?.let { OffText.normalize(it) }.orEmpty()
            if (brandNorm.isEmpty()) {
                0
            } else if (normKeywords.any { kw ->
                    brandNorm == kw || brandNorm.startsWith("$kw ") || brandNorm.endsWith(" $kw") ||
                        (kw.length >= 3 && brandNorm.contains(kw))
                }
            ) {
                1
            } else {
                0
            }
        }
    }

    /**
     * Lit une ligne de résultat.
     *
     * Les boissons d'Open Food Facts sont chiffrées aux 100 ml ; on leur donne la même
     * valeur aux 100 g, car 100 ml de boisson pèsent 100 g à 2 % près. Sans cela, un verre
     * de soda saisi en grammes ne se calculerait pas.
     */
    private fun product(cursor: Cursor): FoodProduct {
        val code = cursor.getString(0).orEmpty()
        val carbs = cursor.getDouble(3)
        return FoodProduct(
            id = "off-$code",
            barcode = code.ifBlank { null },
            name = cursor.getString(1).orEmpty(),
            brand = cursor.getString(2)?.takeIf { it.isNotBlank() },
            carbsPer100g = carbs,
            carbsPer100ml = if (cursor.getInt(4) == 1) carbs else null,
            // La portion et son libellé (« 2 biscuits (25 g) ») sont ce qui permet de
            // convertir « deux biscuits » en grammes au lieu de les redemander.
            defaultPortionGrams = if (cursor.isNull(5)) null else cursor.getDouble(5),
            servingLabel = cursor.getString(6)?.takeIf { it.isNotBlank() },
            packageGrams = if (cursor.isNull(7)) null else cursor.getDouble(7),
            source = FoodSource.OPEN_FOOD_FACTS_CACHE,
        )
    }

    private fun open(): SQLiteDatabase? {
        database?.let { return it }
        if (unavailable) return null
        return synchronized(this) {
            database?.let { return it }
            val fichier = file()
            if (!fichier.isFile) {
                unavailable = true
                return null
            }
            try {
                val base = SQLiteDatabase.openDatabase(
                    fichier.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                )
                avecPortion = aLaColonnePortion(base)
                database = base
                base
            } catch (error: SQLiteException) {
                fail(error)
                null
            }
        }
    }

    /**
     * Vrai si la base installée porte les colonnes de portion.
     *
     * Une base construite avant elles reste parfaitement utilisable : elle ne permet
     * seulement pas de convertir « deux biscuits ». Mieux vaut lire son nom et ses glucides
     * que de la déclarer illisible et d'envoyer chaque aliment sur Internet.
     */
    private fun aLaColonnePortion(base: SQLiteDatabase): Boolean = try {
        base.rawQuery("PRAGMA table_info(produits)", null).use { cursor ->
            var trouve = false
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == "portion") trouve = true
            }
            trouve
        }
    } catch (error: SQLiteException) {
        false
    }

    /** Les colonnes à lire : les vraies si elles existent, des `NULL` sinon. */
    private fun colonnes(): String =
        if (avecPortion) COLONNES_PRODUIT else COLONNES_PRODUIT_SANS_PORTION

    private fun fail(error: SQLiteException) {
        // Une base illisible ne doit pas faire tomber une annonce de repas : on la met de
        // côté pour la session et l'application continue avec le réseau.
        unavailable = true
        database = null
        lastIssue = error.message
    }

    /** Dernier incident, pour l'écran de réglages. */
    @Volatile
    var lastIssue: String? = null
        private set

    companion object {

        /** Nom du fichier attendu dans le dossier de l'application. */
        const val FILE_NAME = "off-base.db"

        /**
         * Nombre de correspondances lues dans l'index avant de les départager.
         *
         * L'index étant rempli par popularité décroissante, ces lignes sont les plus connues
         * de la base : au-delà, le produit n'aurait de toute façon aucune chance de passer
         * devant un aliment courant.
         */
        private const val SCAN_LIMIT = 2000

        /** Colonnes communes aux deux requêtes de produit, pour qu'elles ne divergent pas. */
        private const val COLONNES_PRODUIT =
            "p.code, p.nom, p.marque, p.glucides, p.ml, p.portion, p.portion_libelle, p.paquet"

        /**
         * Les mêmes emplacements, sans les colonnes de portion.
         *
         * Les `NULL` ne sont pas un détail : ils gardent les mêmes indices de curseur, donc
         * une seule fonction de lecture sert les deux schémas.
         */
        private val COLONNES_PRODUIT_SANS_PORTION: String =
            "p.code, p.nom, p.marque, p.glucides, p.ml, NULL, NULL, NULL"
    }
}
