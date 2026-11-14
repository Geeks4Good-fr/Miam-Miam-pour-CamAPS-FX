package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.CarbResult
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.data.mistral.ChatRole
import com.najah.mealannouncer.data.mistral.ChatTurn
import com.najah.mealannouncer.data.mistral.ConversationFood
import com.najah.mealannouncer.data.mistral.ConversationReply
import com.najah.mealannouncer.data.mistral.JsonAsk
import com.najah.mealannouncer.data.mistral.MealConversation
import com.najah.mealannouncer.data.mistral.MistralChat
import com.najah.mealannouncer.data.mistral.MistralException
import com.najah.mealannouncer.data.mistral.WebCarbEstimate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale
import javax.inject.Inject

/** Ce qu'un échange de la conversation a donné. */
data class ConversationStep(
    /** Les lignes du repas, chiffrées par l'application. */
    val lines: List<AssistantLine>,
    /** Ce que l'assistant doit dire maintenant ; `null` quand il n'a plus rien à demander. */
    val question: String?,
    /** Vrai quand la conversation est finie (plus de question). */
    val done: Boolean,
    /** Faux quand la dernière phrase n'a pas été comprise. */
    val understood: Boolean,
    /** Vrai quand l'utilisateur a demandé d'arrêter. */
    val cancelled: Boolean = false,
    val rejected: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /**
     * Ce que la relecture n'a pas pu lever : un écart entre le calcul et l'estimation du
     * diététicien, une vérification impossible, un total trop gros. Non vide → le repas ne part
     * **jamais** seul vers CamAPS : il se vérifie à l'écran.
     */
    val doubts: List<String> = emptyList(),
)

/**
 * La mémoire d'une conversation de repas : l'histoire des échanges, et les valeurs déjà
 * trouvées pour ne pas chercher deux fois le même aliment.
 */
class ConversationSession(val apiKey: String) {
    internal val history = mutableListOf<ChatTurn>()

    // Les aliments d'un repas sont cherchés en parallèle : ces mémoires sont partagées entre fils.
    internal val values: MutableMap<String, Pair<FoodProduct?, WebCarbEstimate?>> =
        Collections.synchronizedMap(mutableMapOf())
    internal val unitInfos: MutableMap<String, WebCarbEstimate?> = Collections.synchronizedMap(mutableMapOf())

    /**
     * Glucides lus sur la photo d'un tableau nutritionnel, rangés par l'identité de l'estimation
     * qu'ils remplacent : l'estimation reste en cache, la photo passe devant.
     */
    internal val photoEstimates: MutableMap<String, WebCarbEstimate> = Collections.synchronizedMap(mutableMapOf())

    /**
     * Fiches d'Open Food Facts retrouvées grâce à une photo d'emballage ou de code-barres, rangées
     * par numéro d'aliment (`food.id`) et par nom normalisé : elles survivent même si le modèle
     * reformule le nom de l'aliment au tour suivant.
     */
    internal val photoProductsById: MutableMap<Int, FoodProduct> = Collections.synchronizedMap(mutableMapOf())
    internal val photoProductsByName: MutableMap<String, FoodProduct> = Collections.synchronizedMap(mutableMapOf())

    /** Nom (clé) sous lequel chaque aliment, par son numéro, a été cherché : survit à un renommage. */
    internal val keyById: MutableMap<Int, String> = Collections.synchronizedMap(mutableMapOf())
    /** Photos par produit ([productIdentity]) : un autre produit retenu a une autre photo. */
    internal val images: MutableMap<String, ProductImage?> = Collections.synchronizedMap(mutableMapOf())

    /** Produits refusés par l'utilisateur pendant ce repas ([productIdentity]) : jamais repris. */
    internal val rejected: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Les lignes du dernier échange : ce que l'utilisateur voit, et ce qu'il peut contester. */
    internal var lastLines: List<AssistantLine> = emptyList()

    /** Lignes pour lesquelles l'application a déjà posé elle-même la question : une fois suffit. */
    internal val askedByApp = mutableSetOf<Long>()

    /** Lignes pour lesquelles « cuit ou cru ? » a déjà été demandé : une fois suffit. */
    internal val askedCooking = mutableSetOf<Long>()

    /** Lignes de la banque pour lesquelles le choix entre les portions en photo a déjà été demandé. */
    internal val askedVisualPortion = mutableSetOf<Long>()
    internal var started = false

    /** Les phrases de la personne, dans l'ordre : la relecture juge d'après SES mots. */
    internal val phrases = mutableListOf<String>()

    /** Les aliments du dernier échange, tels que compris (et corrigés par la relecture). */
    internal var foods: List<ConversationFood> = emptyList()

    /** Lignes sur lesquelles la relecture a déjà posé sa question : une fois suffit. */
    internal val askedByReview = mutableSetOf<Long>()

    /** Ce que la relecture a trouvé, joint à la réponse suivante pour que le modèle sache le contexte. */
    internal var reviewNote: String? = null
}

/**
 * Le dialogue du repas : l'IA comprend et fait préciser, l'application cherche et calcule.
 *
 * Un échange se déroule ainsi :
 * 1. la parole de l'utilisateur part chez le modèle **avec toute l'histoire** ;
 * 2. le modèle rend la liste complète des aliments (et, s'il doute déjà, une question) ;
 * 3. l'application cherche chaque valeur ([MealCarbPipeline.lookup]) et calcule chaque ligne ;
 * 4. s'il reste un aliment non chiffré, l'application envoie un **bilan** au modèle, qui choisit
 *    la question à poser — une seule, sur un seul aliment.
 *
 * Le modèle ne fournit jamais un nombre de glucides : il fournit des noms, des quantités dites,
 * et au plus une estimation de **poids** pour une mesure de ménage, affichée comme telle.
 */
class MealConversationEngine @Inject constructor(
    private val chat: MistralChat,
    private val pipeline: MealCarbPipeline,
    private val reviewer: MealReviewer,
) {

    fun newSession(apiKey: String): ConversationSession = ConversationSession(apiKey)

    /**
     * Écarte le produit retenu pour [line] (bouton « Pas ce produit ») : il ne sera plus jamais
     * repris pendant ce repas. L'échange suivant cherchera une autre valeur.
     */
    fun reject(session: ConversationSession, line: AssistantLine) {
        productIdentity(line.product, line.estimate)?.let { session.rejected += it }
    }

    /** Un échange : [utterance] est la phrase du repas au premier tour, une réponse ensuite. */
    suspend fun turn(
        session: ConversationSession,
        utterance: String,
        onStep: (PipelineStep) -> Unit = {},
    ): ConversationStep = withContext(Dispatchers.IO) {
        val message = if (session.started) {
            MealConversation.answerMessage(utterance, retained(session.lastLines), session.reviewNote)
        } else {
            MealConversation.firstMessage(utterance)
        }
        session.reviewNote = null
        session.started = true
        session.phrases += utterance
        val first = ask(session, message)
        // « Ce n'est pas ce produit » : le produit montré pour cet aliment est écarté du repas.
        first.foods.filter { it.rejectFound }.forEach { food ->
            session.lastLines.firstOrNull { it.id == food.id.toLong() }
                ?.let { reject(session, it) }
        }
        if (first.cancel) {
            return@withContext ConversationStep(
                lines = emptyList(),
                question = null,
                done = true,
                understood = true,
                cancelled = true,
            )
        }

        val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var foods = first.foods
        var lines = resolve(session, foods, warnings, onStep)
        var reply = first
        val missing = lines.filter { it.carbResult() !is CarbResult.Computed }
        val visualPending = missing.firstOrNull { it.portionOptions.size > 1 && it.id !in session.askedByApp }
        // Le modèle a fini ou pose déjà une question, et tout est chiffré : rien à ajouter.
        // Sinon, c'est le bilan de l'application qui lui dit ce qui manque vraiment — sauf si
        // l'aliment manquant propose déjà ses paliers visuels en photo.
        if (missing.isNotEmpty() && visualPending == null && first.question == null && first.understood && lines.isNotEmpty()) {
            reply = ask(session, bilan(lines))
            if (reply.cancel) {
                return@withContext ConversationStep(
                    lines = lines,
                    question = null,
                    done = true,
                    understood = true,
                    cancelled = true,
                )
            }
            if (reply.foods.isNotEmpty()) {
                foods = reply.foods
                lines = resolve(session, foods, warnings, onStep)
            }
        }

        // Quand une fiche de la banque offre plusieurs portions en photo et qu'aucun poids en
        // grammes n'a été dit, la voix propose directement ces paliers avant toute autre question.
        val visualQuestion = if (first.understood) {
            lines.firstOrNull { it.portionOptions.size > 1 && it.needsPortion() && it.id !in session.askedByApp }
                ?.let { line ->
                    session.askedVisualPortion += line.id
                    session.askedByApp += line.id
                    PortionQuestions.forVisualOptions(line)
                }
        } else {
            null
        }

        // Riz, pâtes, semoule… : un poids dit sans « cuit » ni « cru » est ambigu (×2,5 environ).
        // C'est l'application qui pose la question, une fois par ligne : confiée au modèle par le
        // bilan, elle n'arrivait jamais quand la ligne était déjà chiffrée (essai du 10/11/2026).
        val cookingQuestion = if (first.understood && visualQuestion == null) {
            lines.firstOrNull { needsCookingState(it) && it.id !in session.askedCooking }
                ?.let { line ->
                    session.askedCooking += line.id
                    session.reviewNote = "L'application a demandé si le poids de « ${line.rawText.trim()} » " +
                        "est cuit ou cru. Si la personne répond cru, à cuire ou sec, renomme l'aliment " +
                        "« ${line.name} cru » en gardant le même poids ; si elle répond cuit, ne change rien."
                    PortionQuestions.forCookingState(line)
                }
        } else {
            null
        }

        // Une ligne à 0 g ne termine pas la conversation en silence : si le modèle ne demande rien,
        // l'application pose sa propre question de portion — une fois par ligne, pour ne pas boucler.
        var question = visualQuestion ?: cookingQuestion ?: reply.question ?: first.question ?: lines
            .firstOrNull { it.carbResult() !is CarbResult.Computed && it.id !in session.askedByApp }
            ?.let { line ->
                session.askedByApp += line.id
                PortionQuestions.forLine(line)
            }
        // La conversation pense avoir fini : un diététicien relit chaque ligne avant toute annonce.
        var doubts = emptyList<String>()
        if (question == null && lines.isNotEmpty()) {
            val reviewed = review(session, foods, lines, warnings, onStep)
            foods = reviewed.foods
            lines = reviewed.lines
            question = reviewed.question
            doubts = reviewed.doubts
        }
        session.foods = foods
        session.lastLines = lines
        ConversationStep(
            lines = lines,
            question = question,
            done = question == null,
            understood = first.understood,
            rejected = (first.rejected + reply.rejected).distinct(),
            warnings = warnings.distinct(),
            doubts = doubts,
        )
    }

    /** Ce que la relecture a laissé : lignes (corrigées), et soit une question, soit des doutes. */
    private data class Reviewed(
        val foods: List<ConversationFood>,
        val lines: List<AssistantLine>,
        val question: String? = null,
        val doubts: List<String> = emptyList(),
    )

    /**
     * La relecture (`docs/DECISIONS.md` § 43) : le diététicien estime chaque ligne ; une ligne qu'il
     * juge fausse, ou dont le calcul s'écarte nettement de son estimation, est corrigée (autre
     * produit, bon poids, ou sa valeur quand aucune fiche ne convient), puis relue une seconde fois.
     * Ce qui reste douteux devient une question — une fois par ligne — ou un doute qui empêche
     * l'envoi automatique vers CamAPS.
     */
    private suspend fun review(
        session: ConversationSession,
        foods: List<ConversationFood>,
        lines: List<AssistantLine>,
        warnings: MutableList<String>,
        onStep: (PipelineStep) -> Unit,
    ): Reviewed {
        var currentFoods = foods
        var currentLines = lines
        repeat(MAX_REVIEW_ROUNDS) { round ->
            onStep(PipelineStep(index = round + 1, total = MAX_REVIEW_ROUNDS, name = "vérification des glucides"))
            val result = try {
                reviewer.review(session.apiKey, session.phrases, currentLines)
            } catch (error: MistralException) {
                return Reviewed(
                    currentFoods,
                    currentLines,
                    doubts = listOf("Les glucides n'ont pas pu être vérifiés (${error.message})."),
                )
            }
            val flagged = currentLines.mapNotNull { line ->
                val opinion = result.line(line.id) ?: return@mapNotNull null
                val estimate = opinion.estimatedCarbs
                val computed = line.carbResult() is CarbResult.Computed
                // L'écart chiffré décide, pas l'adjectif : « pas plausible » pour 18 g calculés contre
                // 16,5 g estimés (essai du 29/10) ne vaut pas une question. Sans estimation, l'avis suffit.
                val wrong = if (estimate != null) {
                    !computed || CarbSanity.discrepant(line.carbs.grams, estimate)
                } else {
                    !opinion.plausible
                }
                if (wrong) line to opinion else null
            }
            if (flagged.isEmpty()) {
                val tooLarge = CarbSanity.tooLarge(currentLines.map { it.carbs.grams })
                return Reviewed(
                    currentFoods,
                    currentLines,
                    doubts = if (tooLarge) listOf(TOO_LARGE) else emptyList(),
                )
            }
            if (round == MAX_REVIEW_ROUNDS - 1) {
                return unresolved(session, currentFoods, currentLines, flagged, result.question)
            }
            // Les corrections du diététicien : autre produit (l'ancien est écarté), ou bon poids.
            var changed = false
            currentFoods = currentFoods.map { food ->
                val (line, opinion) = flagged.firstOrNull { it.first.id == food.id.toLong() } ?: return@map food
                val correction = opinion.correction ?: return@map food
                val newName = correction.name
                when {
                    newName != null && CarbSanity.normalize(newName) != CarbSanity.normalize(food.name) -> {
                        val oldBankId = line.product?.id?.let { pipeline.bankFood(it)?.id }
                        val newBankId = pipeline.bankFoodId(newName)
                        if (oldBankId == null || oldBankId != newBankId) {
                            productIdentity(line.product, line.estimate)?.let { session.rejected += it }
                        }
                        changed = true
                        val grams = correction.grams
                        if (grams == null) {
                            food.copy(name = newName)
                        } else {
                            // Le bon produit ET le bon poids (« pâtes cuites », 250 g) : le poids remplace
                            // la quantité dite, qui portait sur l'autre forme.
                            val liquid = food.unit == MealUnit.MILLILITER || food.unit == MealUnit.CUP
                            food.copy(
                                name = newName,
                                quantity = grams,
                                unit = if (liquid) MealUnit.MILLILITER else MealUnit.GRAM,
                                grams = grams,
                                assumption = "revu : ${opinion.problem ?: "produit et poids corrigés"}",
                            )
                        }
                    }
                    correction.grams != null -> {
                        changed = true
                        val liquid = food.unit == MealUnit.MILLILITER || food.unit == MealUnit.CUP
                        food.copy(
                            quantity = correction.grams,
                            unit = if (liquid) MealUnit.MILLILITER else MealUnit.GRAM,
                            grams = correction.grams,
                            assumption = "revu : ${opinion.problem ?: "poids corrigé"}",
                        )
                    }
                    else -> food
                }
            }
            if (changed) currentLines = resolve(session, currentFoods, warnings, onStep)
            // Quand aucune fiche ne convient, la valeur du diététicien chiffre la ligne, affichée comme telle.
            currentLines = currentLines.map { line ->
                val opinion = flagged.firstOrNull { it.first.id == line.id }?.second ?: return@map line
                val carbs = opinion.correction?.carbs ?: return@map line
                val stillWrong = line.carbResult() !is CarbResult.Computed || CarbSanity.discrepant(line.carbs.grams, carbs)
                if (stillWrong) reviewerLine(line, carbs, opinion.problem) else line
            }
        }
        return Reviewed(currentFoods, currentLines)
    }

    /** Après deux relectures, un écart demeure : une question (une fois par ligne), sinon un doute. */
    private fun unresolved(
        session: ConversationSession,
        foods: List<ConversationFood>,
        lines: List<AssistantLine>,
        flagged: List<Pair<AssistantLine, com.najah.mealannouncer.data.mistral.ReviewedLine>>,
        reviewQuestion: String?,
    ): Reviewed {
        val (worst, opinion) = flagged.maxBy { (line, opinion) ->
            kotlin.math.abs(line.carbs.grams - (opinion.estimatedCarbs ?: line.carbs.grams))
        }
        val estimate = opinion.estimatedCarbs?.let { Carbs.of(it).format() } ?: "?"
        val summary = flagged.joinToString(" ; ") { (line, o) ->
            "${line.name} : ${line.carbs.format()} g calculés, ${o.estimatedCarbs?.let { Carbs.of(it).format() } ?: "?"} g estimés" +
                (o.problem?.let { " ($it)" } ?: "")
        }
        if (worst.id !in session.askedByReview) {
            session.askedByReview += worst.id
            session.reviewNote = summary
            val question = reviewQuestion ?: "Pour ${worst.name}, la base donne ${worst.carbs.format()} grammes de " +
                "glucides, alors que j'en attendais environ $estimate. C'était quoi exactement, et combien ?"
            return Reviewed(foods, lines, question = question)
        }
        return Reviewed(foods, lines, doubts = listOf("Écart non résolu — $summary."))
    }

    /** Une ligne chiffrée par la valeur du diététicien, quand aucune fiche ne convient. */
    private fun reviewerLine(line: AssistantLine, carbs: Double, problem: String?): AssistantLine = line.copy(
        quantityText = "1",
        unit = MealUnit.UNIT,
        product = null,
        estimate = WebCarbEstimate(
            name = line.name,
            carbsPer100g = 0.0,
            sourceUrl = null,
            general = true,
            carbsPerUnit = carbs,
        ),
        portionNote = "estimation du diététicien" + (problem?.let { " : $it" } ?: ""),
        image = null,
    )

    private fun ask(session: ConversationSession, message: String): ConversationReply {
        val answer = chat.askJson(
            apiKey = session.apiKey,
            ask = JsonAsk(
                system = MealConversation.SYSTEM_PROMPT,
                user = message,
                temperature = TEMPERATURE,
                history = session.history.toList(),
            ),
        )
        // L'histoire ne garde que ce qui a été réellement échangé : une réponse illisible lève
        // une exception avant d'y entrer, et l'échange pourra être rejoué.
        val reply = MealConversation.parse(answer)
        session.history += ChatTurn(ChatRole.USER, message)
        session.history += ChatTurn(ChatRole.ASSISTANT, answer)
        return reply
    }

    /** Les aliments compris deviennent des lignes chiffrées par l'application. */
    private suspend fun resolve(
        session: ConversationSession,
        foods: List<ConversationFood>,
        warnings: MutableList<String>,
        onStep: (PipelineStep) -> Unit,
    ): List<AssistantLine> = coroutineScope {
        // Tous les aliments en même temps : un repas de trois aliments ne coûte plus trois attentes.
        foods.mapIndexed { index, said -> async { resolveOne(session, index, foods.size, said, warnings, onStep) } }
            .awaitAll()
    }

    private suspend fun resolveOne(
        session: ConversationSession,
        index: Int,
        count: Int,
        said: ConversationFood,
        warnings: MutableList<String>,
        onStep: (PipelineStep) -> Unit,
    ): AssistantLine {
        val food = CarbSanity.fixSugarCubes(said)
        val branded = food.brand != null
        onStep(PipelineStep(index = index + 1, total = count, name = food.name))
        val key = food.name.trim().lowercase(Locale.ROOT)
        // Le modèle renomme parfois un aliment d'un échange à l'autre (« chips Carrefour petit
        // paquet » → « chips classiques Carrefour paquet individuel ») : même numéro et même aliment,
        // on reprend la valeur déjà trouvée au lieu d'une nouvelle recherche (38 s le 10/11/2026).
        if (key !in session.values) {
            // … sauf si le renommage change l'état de cuisson (« riz » → « riz cru ») : les
            // glucides pour 100 g ne sont plus les mêmes, il faut chercher à nouveau.
            session.keyById[food.id]?.takeIf { old -> sameFood(old, key) && saysRaw(old) == saysRaw(key) }
                ?.let { old -> session.values[old] }
                ?.let { session.values[key] = it }
        }
        session.keyById[food.id] = key
        // Une fiche Open Food Facts trouvée à partir d'une photo de l'emballage ou du code-barres
        // passe avant toute autre recherche pour cet aliment.
        val fromPhotoProduct = (session.photoProductsById[food.id]
            ?: session.photoProductsByName[key]
            ?: session.photoProductsByName.entries.firstOrNull { (k, _) -> sameFood(k, key) }?.value)
            ?.takeIf { productIdentity(it, null) !in session.rejected }
        if (fromPhotoProduct != null) {
            session.values[key] = fromPhotoProduct to null
        }
        // Une valeur déjà trouvée sert tant que l'utilisateur ne l'a pas refusée.
        val cached = session.values[key]?.takeIf { (p, e) -> productIdentity(p, e) !in session.rejected }
        var (product, estimate) = cached
            ?: pipeline.lookup(
                session.apiKey,
                food.name,
                warnings,
                exclude = snapshot(session.rejected),
                branded = branded,
                webForBranded = false,
            ).also { session.values[key] = it }
        // Une boisson ne prend jamais la valeur d'une poudre, de feuilles ou d'un sirop : la fiche est
        // écartée et on cherche une seconde fois ; à défaut, la ligne reste à chiffrer.
        repeat(2) {
            if (!CarbSanity.incompatible(food, product)) return@repeat
            productIdentity(product, null)?.let { session.rejected += it }
            warnings += "Fiche « ${product?.displayName()} » écartée pour « ${food.name} » (forme sèche ou en poudre)."
            val retry = pipeline.lookup(
                session.apiKey,
                food.name,
                warnings,
                exclude = snapshot(session.rejected),
                branded = branded,
                webForBranded = false,
            )
            session.values[key] = retry
            product = retry.first
            estimate = retry.second
        }
        if (CarbSanity.incompatible(food, product)) product = null
        // Glucides lus sur la photo du tableau nutritionnel : ils passent devant l'estimation.
        var carbsFromPhoto = false
        if (product == null) {
            (productIdentity(null, estimate)?.let { session.photoEstimates[it] }
                ?: session.photoEstimates[PHOTO_ID_KEY + food.id])?.let {
                estimate = it
                carbsFromPhoto = true
            }
        }
        // Le produit est connu mais la pièce ne se chiffre pas : on va lire la fiche de l'emballage
        // sur Internet (glucides ou poids d'une pièce) avant de se rabattre sur une estimation.
        var needsUnit = branded && product != null && food.unit.isCounted() && food.quantity != null &&
            lineFor(food, product, estimate, unitInfo = null, useGuess = false).carbResult() !is CarbResult.Computed
        // D'abord la fiche en ligne du même code-barres (0,1 s) : elle porte souvent la portion
        // (« Un biscuit 20 g ») que la base locale n'a pas. Internet seulement si elle ne suffit pas.
        val local = product
        if (needsUnit && local != null) {
            val online = pipeline.onlineSheet(local)
            if (online != null && !CarbSanity.incompatible(food, online) &&
                lineFor(food, online, estimate, unitInfo = null, useGuess = false).carbResult() is CarbResult.Computed
            ) {
                product = online
                session.values[key] = online to estimate
                needsUnit = false
            }
        }
        // Puis, dans l'ordre : la valeur lue sur une photo de l'emballage (déjà en cache), le poids
        // estimé par le modèle (aucune attente, la ligne est alors marquée « à préciser par photo »),
        // et seulement sans estimation la recherche web (9 à 26 s, mesuré le 10/11/2026).
        val fromPhoto = productIdentity(product, null)?.let { session.unitInfos[PHOTO_KEY + it] }
        val unitInfo = if (needsUnit) {
            when {
                fromPhoto != null -> fromPhoto
                key in session.unitInfos -> session.unitInfos[key]
                food.grams != null -> null
                else -> pipeline.unitInfo(session.apiKey, food.name, warnings).also { session.unitInfos[key] = it }
            }
        } else {
            null
        }
        val identity = productIdentity(product, estimate)
        val image = when {
            identity == null -> null
            identity in session.images -> session.images[identity]
            else -> pipeline.imageFor(product, estimate).also { session.images[identity] = it }
        }
        val options = product?.let { pipeline.portionOptions(it.id) }.orEmpty()
        val lineId = food.id.toLong()
        val askedVisual = synchronized(session.askedVisualPortion) { lineId in session.askedVisualPortion }
        val baseLine = when {
            // Plusieurs portions en photo disponibles et aucun poids en grammes n'a été dit :
            // on laisse l'utilisateur choisir parmi les paliers (à la voix ou à l'écran).
            options.size > 1 && !askedVisual && !hasExplicitWeight(food.rawText) ->
                AssistantLine(
                    id = lineId,
                    name = product?.displayName() ?: estimate?.name ?: food.name,
                    rawText = food.rawText,
                    quantityText = "",
                    unit = MealUnit.GRAM,
                    product = product,
                    estimate = estimate,
                    image = image,
                )

            // Réponse orale après la question des paliers (« la moyenne », « la deuxième », « 200 »).
            options.size > 1 && askedVisual -> {
                val lastPhrase = synchronized(session.phrases) { session.phrases.lastOrNull().orEmpty() }
                val picked = pickVisualOption(lastPhrase, options)
                if (picked != null) {
                    AssistantLine(
                        id = lineId,
                        name = product?.displayName() ?: estimate?.name ?: food.name,
                        rawText = food.rawText,
                        quantityText = formatQuantityText(picked.grams),
                        unit = MealUnit.GRAM,
                        product = product,
                        estimate = estimate,
                        portionNote = picked.label,
                        image = image,
                    )
                } else {
                    lineFor(food, product, estimate, unitInfo).copy(image = image)
                }
            }

            else -> lineFor(food, product, estimate, unitInfo).copy(image = image)
        }
        // Le poids d'une pièce n'est qu'estimé alors que l'emballage le dit : doute à lever par photo.
        val guessed = needsUnit && unitInfo == null && product != null &&
            baseLine.unit == MealUnit.GRAM && baseLine.carbResult() is CarbResult.Computed
        // Produit de marque absent d'Open Food Facts : ses glucides ne sont qu'une moyenne.
        val carbsGuessed = branded && product == null && !carbsFromPhoto && estimate?.general == true
        return baseLine.copy(weightGuessed = guessed, carbsGuessed = carbsGuessed, portionOptions = options)
    }

    /**
     * Retient les glucides pour 100 g lus sur la photo du tableau nutritionnel de [line] (une
     * ligne sans fiche Open Food Facts). Rend `false` si la ligne n'a pas d'estimation à remplacer.
     */
    fun rememberCarbsFromPhoto(session: ConversationSession, line: AssistantLine, estimate: WebCarbEstimate): Boolean {
        if (line.product != null) return false
        val identity = productIdentity(null, line.estimate) ?: return false
        session.photoEstimates[identity] = estimate
        // Aussi par numéro d'aliment : le modèle reprend souvent le nom lu sur la photo
        // (« pâté en croûte Simpl » → « Le Classique – Pâté en croûte Monterrat », essai du
        // 10/11/2026), et la nouvelle estimation n'aurait plus la même identité.
        val ids = synchronized(session.keyById) {
            session.keyById.filter { (_, key) ->
                session.values[key]?.let { (p, e) -> productIdentity(p, e) } == identity
            }.keys.toList()
        }
        ids.forEach { session.photoEstimates[PHOTO_ID_KEY + it] = estimate }
        return true
    }

    /**
     * Deux noms du même aliment d'un échange à l'autre : même premier mot, c'est-à-dire le nom
     * de l'aliment (« chips », « pâté »). Une marque ou un format en commun ne suffit pas.
     */
    private fun sameFood(old: String, new: String): Boolean {
        val a = CarbSanity.normalize(old).split(Regex("[^a-z0-9]+")).firstOrNull { it.length > 2 }
        val b = CarbSanity.normalize(new).split(Regex("[^a-z0-9]+")).firstOrNull { it.length > 2 }
        return a != null && a == b
    }

    /**
     * Retient ce qu'une photo de l'emballage a appris sur la pièce du produit de [line] : les
     * échanges suivants chiffrent la ligne avec, au lieu du poids estimé. Rend `false` si la
     * conversation ne connaît pas ce produit (rien n'est alors retenu).
     */
    fun rememberUnitFromPhoto(session: ConversationSession, line: AssistantLine, unit: WebCarbEstimate): Boolean {
        // Rangé sous l'identité du produit : le modèle peut renommer l'aliment d'un échange à l'autre.
        val identity = productIdentity(line.product, null) ?: return false
        session.unitInfos[PHOTO_KEY + identity] = unit
        return true
    }

    /**
     * Retient la fiche Open Food Facts retrouvée à partir d'une photo de code-barres ou d'emballage,
     * soit pour une ligne existante ([line]), soit pour un nouvel aliment photographié en cours de
     * repas ([spokenName]).
     */
    fun rememberProductFromPhoto(
        session: ConversationSession,
        line: AssistantLine?,
        product: FoodProduct,
        spokenName: String? = null,
        unit: WebCarbEstimate? = null,
    ): Boolean {
        if (!product.hasCarbData()) return false
        line?.let { target ->
            val foodId = target.id.toInt()
            session.photoProductsById[foodId] = product
            session.keyById[foodId]?.let { oldKey ->
                session.values[oldKey] = product to null
                session.photoProductsByName[oldKey] = product
            }
            val lineKey = target.name.trim().lowercase(Locale.ROOT)
            if (lineKey.isNotEmpty()) {
                session.values[lineKey] = product to null
                session.photoProductsByName[lineKey] = product
            }
        }
        listOfNotNull(spokenName, product.name, product.displayName())
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .forEach { key ->
                session.values[key] = product to null
                session.photoProductsByName[key] = product
            }
        if (unit != null) {
            productIdentity(product, null)?.let { id ->
                session.unitInfos[PHOTO_KEY + id] = unit
            }
        }
        return true
    }

    /** Copie d'un ensemble partagé, prise sous son verrou. */
    private fun snapshot(set: MutableSet<String>): Set<String> = synchronized(set) { set.toSet() }

    /** Ce que l'utilisateur voit à l'écran, ligne par ligne, pour qu'il puisse le contester. */
    private fun retained(lines: List<AssistantLine>): List<String> = lines
        .filter { it.product != null || it.estimate != null }
        .map { "#${it.id} ${it.name} (${sourceOf(it)})" }

    /** Le bilan envoyé au modèle : ce que l'application sait de chaque ligne, sans rien cacher. */
    internal fun bilan(lines: List<AssistantLine>): String = buildString {
        appendLine("BILAN DE L'APPLICATION (c'est elle qui calcule les glucides, pas toi) :")
        lines.forEach { line ->
            append("- #").append(line.id).append(' ').append(line.name).append(" : ")
            append(sourceOf(line))
            when (val result = line.carbResult()) {
                is CarbResult.Computed -> {
                    append(" ; ").append(line.quantityText).append(' ').append(unitLabel(line.unit))
                    append(" → ").append(result.carbs.format()).append(" g de glucides.")
                }

                is CarbResult.Unavailable -> append(" ; NON CHIFFRÉ : ").append(result.explanation)
            }
            appendLine()
        }
        val total = Carbs.of(lines.sumOf { it.carbs.grams })
        appendLine("Total déjà chiffré : ${total.format()} g.")
        append("Pose UNE question pour ce qui manque, ou done=true si la personne a dit que c'était tout.")
    }

    private fun saysRaw(name: String): Boolean = RAW_STATE.containsMatchIn(name)

    /** Ligne de la banque, chiffrée « cuit », dont le poids a été dit sans état de cuisson. */
    private fun needsCookingState(line: AssistantLine): Boolean {
        val product = line.product ?: return false
        if (product.id.endsWith(":cru")) return false
        if (pipeline.bankFood(product.id)?.cooking?.changesWithCooking != true) return false
        return PortionQuestions.weightWithoutCookingState(line.rawText)
    }

    /**
     * D'où vient la valeur de [line], pour le journal : photo d'emballage, photo du tableau
     * nutritionnel, ou rien de particulier (null).
     */
    fun photoOrigin(session: ConversationSession, line: AssistantLine): String? {
        val id = line.id.toInt()
        return when {
            session.photoProductsById[id] != null -> "photo d'emballage"
            session.photoEstimates[PHOTO_ID_KEY + id] != null -> "photo du tableau"
            else -> null
        }
    }

    private fun sourceOf(line: AssistantLine): String {
        val product = line.product
        val estimate = line.estimate
        return when {
            product != null -> buildString {
                append("fiche « ").append(product.displayName()).append(" »")
                // Riz, pâtes… : la banque chiffre le CUIT ; un poids dit sans état est ambigu.
                pipeline.bankFood(product.id)?.takeIf { it.cooking?.changesWithCooking == true && !product.id.endsWith(":cru") }?.let {
                    // La question « cuit ou cru ? » est posée par l'application (needsCookingState).
                    append(" (banque de portions, valeurs pour l'aliment CUIT ; si la personne dit cru ou à cuire, ")
                    append("nomme l'aliment « … cru »)")
                }
                product.servingLabel?.takeIf { it.isNotBlank() }?.let {
                    append(", portion « ").append(it).append(" »")
                }
                if (line.portionOptions.size > 1) {
                    val paliers = line.portionOptions.joinToString(", ") { opt ->
                        "${formatQuantityText(opt.grams)} g" + (opt.label?.let { " ($it)" } ?: "")
                    }
                    append(", portions en photo proposées : ").append(paliers)
                }
            }

            estimate?.general == true -> "estimation moyenne (${estimate.carbsPer100g} g pour 100 g)"
            estimate != null -> "valeur web (${estimate.carbsPer100g} g pour 100 g)"
            else -> "AUCUNE VALEUR trouvée (base, Internet, estimation)"
        }
    }

    internal companion object {
        /** Un peu de souplesse pour parler naturellement ; les nombres, eux, sont bornés à la lecture. */
        const val TEMPERATURE = 0.2

        /** Relecture, correction, seconde relecture : au-delà, on demande ou on doute. */
        const val MAX_REVIEW_ROUNDS = 2

        /** Préfixe des valeurs « par pièce » lues sur une photo, rangées par produit. */
        const val PHOTO_KEY = "photo:"

        /** Préfixe des glucides lus sur une photo, rangés par numéro d'aliment. */
        const val PHOTO_ID_KEY = "photo-id:"

        /** Un nom qui dit l'aliment cru (mêmes mots que la banque de portions). */
        val RAW_STATE = Regex("""\b(cru|crus|crue|crues|cuire|sec|secs|seche|seches|sèche|sèches|non cuits?|avant cuisson)\b""")

        const val TOO_LARGE = "Repas très chargé en glucides : vérifie chaque ligne à l'écran avant CamAPS."

        private val EXPLICIT_WEIGHT_REGEX = Regex(
            "\\b(\\d+(?:[.,]\\d+)?|vingt|trente|quarante|cinquante|soixante|quatre[- ]vingts?|cent|deux\\s+cents|trois\\s+cents|quatre\\s+cents|cinq\\s+cents)\\s*(?:g|gr|grammes?|kg|kilos?|ml|millilitres?|cl|dl)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** Vrai quand l'utilisateur a dicté un poids ou un volume explicite (« 200 g », « 150 grammes »). */
        internal fun hasExplicitWeight(rawText: String): Boolean =
            EXPLICIT_WEIGHT_REGEX.containsMatchIn(rawText)

        /**
         * Reconnaît dans la réponse orale l'un des paliers en photo proposés à l'écran
         * (« 200 », « 200 grammes », « la petite », « la moyenne », « la grande », « la deuxième »).
         */
        internal fun pickVisualOption(
            utterance: String,
            options: List<com.najah.mealannouncer.feature.assistant.portions.PortionOption>,
        ): com.najah.mealannouncer.feature.assistant.portions.PortionOption? {
            if (options.isEmpty() || utterance.isBlank()) return null
            val norm = CarbSanity.normalize(utterance)
            // 1. Nombre dit en chiffres ou en lettres correspondant à l'un des paliers.
            val spokenNumbers = Regex("\\b(\\d+(?:[.,]\\d+)?)\\b").findAll(utterance)
                .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
                .toList() + spokenFrenchNumbers(norm)
            for (num in spokenNumbers) {
                options.firstOrNull { kotlin.math.abs(it.grams - num) < 0.5 }?.let { return it }
            }
            // 2. Désignation par taille ou par rang (« la petite », « la moyenne », « la deuxième »…).
            val words = norm.split(Regex("\\s+")).toSet()
            return when {
                words.any { it in setOf("petit", "petite", "premier", "premiere", "1er", "1ere") } ->
                    options.first()
                words.any { it in setOf("moyen", "moyenne", "milieu", "deuxieme", "2e", "2eme", "seconde") } ->
                    options[options.size / 2]
                words.any { it in setOf("grand", "grande", "gros", "grosse", "dernier", "derniere") } ->
                    options.last()
                words.any { it in setOf("troisieme", "3e", "3eme") } && options.size >= 3 ->
                    options[2]
                words.any { it in setOf("quatrieme", "4e", "4eme") } && options.size >= 4 ->
                    options[3]
                else -> null
            }
        }

        private fun spokenFrenchNumbers(norm: String): List<Double> = buildList {
            val map = listOf(
                "cinq cents" to 500.0,
                "quatre cents" to 400.0,
                "trois cents" to 300.0,
                "deux cent cinquante" to 250.0,
                "deux cents" to 200.0,
                "deux cent" to 200.0,
                "cent cinquante" to 150.0,
                "cent vingt" to 120.0,
                "cent dix" to 110.0,
                "cent" to 100.0,
                "quatre vingt dix" to 90.0,
                "quatre vingts" to 80.0,
                "quatre vingt" to 80.0,
                "soixante dix" to 70.0,
                "soixante" to 60.0,
                "cinquante" to 50.0,
                "quarante" to 40.0,
                "trente" to 30.0,
                "vingt" to 20.0,
            )
            for ((phrase, value) in map) {
                if (Regex("(^| )${Regex.escape(phrase)}( |$)").containsMatchIn(norm)) add(value)
            }
        }

        /**
         * Une ligne à partir de ce que la conversation a compris, dans l'ordre de confiance :
         *
         * 1. la quantité dite, chiffrée par la fiche trouvée (Open Food Facts, ou fiche web qui
         *    donne les glucides ou le poids d'une pièce) ;
         * 2. pour un produit d'Open Food Facts qui ne sait pas chiffrer une pièce, la fiche
         *    « par pièce » lue sur Internet ([unitInfo]) ;
         * 3. en dernier recours ([useGuess]), le poids estimé par le modèle, hypothèse affichée.
         */
        fun lineFor(
            food: ConversationFood,
            product: FoodProduct?,
            estimate: WebCarbEstimate?,
            unitInfo: WebCarbEstimate? = null,
            useGuess: Boolean = true,
        ): AssistantLine {
            val unit = if (food.unit == MealUnit.UNKNOWN) MealUnit.GRAM else food.unit
            val said = AssistantLine(
                id = food.id.toLong(),
                name = product?.displayName() ?: estimate?.name ?: food.name,
                rawText = food.rawText,
                quantityText = food.quantity?.let { formatQuantityText(it) }.orEmpty(),
                unit = unit,
                product = product,
                estimate = estimate,
                portionNote = estimate?.unitDetail?.takeIf { unit.isCounted() && estimate.knowsUnit },
            )
            if (said.carbResult() is CarbResult.Computed) return said

            val quantity = food.quantity
            if (product != null && unitInfo != null && quantity != null && unit.isCounted()) {
                val perUnit = unitInfo.carbsPerUnit
                val unitGrams = unitInfo.unitGrams
                val fromSheet = when {
                    perUnit != null -> said.copy(
                        product = product.copy(carbsPerUnit = perUnit),
                        portionNote = unitInfo.unitDetail ?: "glucides d'une pièce : fiche de l'emballage",
                    )

                    unitGrams != null -> said.copy(
                        quantityText = formatQuantityText(quantity * unitGrams),
                        unit = MealUnit.GRAM,
                        portionNote = "${formatQuantityText(quantity)} × ${formatQuantityText(unitGrams)} g" +
                            (unitInfo.unitDetail?.let { " ($it)" } ?: ""),
                    )

                    else -> null
                }
                if (fromSheet != null && fromSheet.carbResult() is CarbResult.Computed) return fromSheet
            }

            if (!useGuess) return said
            val grams = food.grams ?: return said
            // Une boisson mesurée en volume reste en millilitres si la fiche le permet.
            val liquid = food.unit == MealUnit.MILLILITER || food.unit == MealUnit.CUP
            val guessUnit = if (liquid && product?.carbsPer100ml != null) MealUnit.MILLILITER else MealUnit.GRAM
            return said.copy(
                quantityText = formatQuantityText(grams),
                unit = guessUnit,
                portionNote = food.assumption ?: "poids estimé",
            )
        }
    }
}
