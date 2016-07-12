/*
 * Copyright (c) 2016 Regents of the University of Minnesota.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.umn.biomedicus.concepts;

import edu.umn.biomedicus.annotations.DocumentScoped;
import edu.umn.biomedicus.application.DocumentProcessor;
import edu.umn.biomedicus.common.labels.Label;
import edu.umn.biomedicus.common.labels.Labels;
import edu.umn.biomedicus.common.semantics.Concept;
import edu.umn.biomedicus.common.simple.SimpleTerm;
import edu.umn.biomedicus.common.syntax.PartOfSpeech;
import edu.umn.biomedicus.common.syntax.PartsOfSpeech;
import edu.umn.biomedicus.common.terms.TermsBag;
import edu.umn.biomedicus.common.text.*;
import edu.umn.biomedicus.exc.BiomedicusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

import static edu.umn.biomedicus.common.syntax.PartOfSpeech.*;

/**
 * Uses a {@link edu.umn.biomedicus.concepts.ConceptModel} to recognize concepts in text. First, it will
 * try to find direct matches against all in-order sublists of tokens in a sentence. Then it will perform syntactic
 * permutations on any prepositional phrases in those sublists.
 *
 * @author Ben Knoll
 * @author Serguei Pakhomov
 * @since 1.0.0
 */
@DocumentScoped
class DictionaryConceptRecognizer implements DocumentProcessor {

    /**
     * Class logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(DictionaryConceptRecognizer.class);

    /**
     * Trivial parts of speech.
     */
    private static final Set<PartOfSpeech> TRIVIAL_POS;

    /**
     * Initialization for trivial parts of speech.
     */
    static {
        Set<PartOfSpeech> builder = new HashSet<>();
        Collections.addAll(builder,
                DT,
                CD,
                WDT,
                TO,
                CC,
                PRP,
                PRP$,
                MD,
                EX,
                IN,
                XX);

        Set<PartOfSpeech> punctuationClass = PartsOfSpeech.getPunctuationClass();
        builder.addAll(punctuationClass);

        TRIVIAL_POS = Collections.unmodifiableSet(builder);
    }

    /**
     * The maximum size of a term span.
     */
    private static final int SPAN_SIZE = 5;

    /**
     * The concept dictionary to look up concepts from.
     */
    private final ConceptModel conceptModel;
    private final Labels<Sentence2> sentences;
    private final Labels<NormIndex> normIndexes;
    private final Labels<TermToken> termTokens;
    private final Document document;
    private final Labels<AcronymExpansion> acronymExpansions;
    private final Labels<PartOfSpeech> partsOfSpeech;

    /**
     * Creates a dictionary concept recognizer from a concept dictionary and a document.
     *
     * @param conceptModel the dictionary to get concepts from.
     */
    @Inject
    DictionaryConceptRecognizer(ConceptModel conceptModel,
                                Document document,
                                Labels<Sentence2> sentences,
                                Labels<AcronymExpansion> acronymExpansions,
                                Labels<NormIndex> normIndexes,
                                Labels<TermToken> termTokens,
                                Labels<PartOfSpeech> partsOfSpeech) {
        this.document = document;
        this.conceptModel = conceptModel;
        this.sentences = sentences;
        this.acronymExpansions = acronymExpansions;
        this.normIndexes = normIndexes;
        this.termTokens = termTokens;
        this.partsOfSpeech = partsOfSpeech;
    }

    private boolean checkPhrase(Span span, String phrase, boolean oneToken, double confMod) throws BiomedicusException {
        List<SuiCuiTui> phraseSUI = conceptModel.forPhrase(phrase);

        if (phraseSUI != null) {
            makeTerm(span, phraseSUI, 1 - confMod);
            return true;
        }

        if (oneToken) {
            return false;
        }

        phraseSUI = conceptModel.forLowercasePhrase(phrase.toLowerCase());

        if (phraseSUI != null) {
            makeTerm(span, phraseSUI, 0.6 - confMod);
            return true;
        }

        return false;
    }

    private void checkTokenSet(List<Label<TermToken>> tokenSet) {
        if (tokenSet.size() <= 1) {
            return;
        }

        Span phraseAsSpan = new Span(tokenSet.get(0).getBegin(), tokenSet.get(tokenSet.size() - 1).getEnd());
        TermsBag.Builder builder = TermsBag.builder();
        for (Label<NormIndex> normIndexLabel : normIndexes.insideSpan(phraseAsSpan)) {
            Optional<Label<PartOfSpeech>> partOfSpeechLabel = partsOfSpeech.withSpan(normIndexLabel);
            if (partOfSpeechLabel.isPresent() && TRIVIAL_POS.contains(partOfSpeechLabel.get().value())) {
                continue;
            }

            builder.addTerm(normIndexLabel.value().term());
        }
        TermsBag normVector = builder.build();

        List<SuiCuiTui> normsCUI = conceptModel.forNorms(normVector);
        if (normsCUI != null) {
            makeTerm(phraseAsSpan, normsCUI, .3);
        }
    }

    private void makeTerm(SpanLike spanLike, List<SuiCuiTui> cuis, double confidence) {
        List<Concept> concepts = cuis.stream()
                .map(suiCuiTui -> suiCuiTui.toConcept(confidence)).collect(Collectors.toList());

        Term term = new SimpleTerm(spanLike.getBegin(), spanLike.getEnd(), concepts.get(0),
                concepts.subList(1, concepts.size()));
        document.addTerm(term);
    }

    @Override
    public void process() throws BiomedicusException {
        LOGGER.info("Finding concepts in document.");
        String documentText = document.getText();
        for (Label<Sentence2> sentence : sentences) {
            LOGGER.trace("Identifying concepts in a sentence");

            StringBuilder editedString = new StringBuilder();
            List<Span> editedStringSpans = new ArrayList<>();
            List<Label<TermToken>> sentenceTermTokens = termTokens.insideSpan(sentence).all();

            for (Label<TermToken> sentenceTermToken : sentenceTermTokens) {
                Optional<Label<AcronymExpansion>> acronymForToken = acronymExpansions.withSpan(sentenceTermToken);
                TokenLike tokenLike;
                if (acronymForToken.isPresent()) {
                    tokenLike = acronymForToken.get().value();
                } else {
                    tokenLike = sentenceTermToken.value();
                }
                String tokenText = tokenLike.getText();
                String trailingText = tokenLike.getTrailingText();
                Span span = new Span(editedString.length(), editedString.length() + tokenText.length());
                editedString.append(tokenText);
                editedString.append(trailingText);
                editedStringSpans.add(span);
            }

            for (int from = 0; from < sentenceTermTokens.size(); from++) {
                int to = Math.min(from + SPAN_SIZE, sentenceTermTokens.size());
                List<Label<TermToken>> window = sentenceTermTokens.subList(from, to);

                Label<TermToken> firstTokenLabel = window.get(0);
                boolean firstTokenAllTrivial = partsOfSpeech.insideSpan(firstTokenLabel)
                        .all()
                        .stream()
                        .map(Label::value)
                        .allMatch(TRIVIAL_POS::contains);

                for (int subsetSize = 1; subsetSize <= window.size(); subsetSize++) {
                    List<Label<TermToken>> windowSubset = window.subList(0, subsetSize);
                    int last = subsetSize - 1;
                    Label<TermToken> lastTokenLabel = windowSubset.get(last);
                    Span asSpan = new Span(firstTokenLabel.getBegin(), lastTokenLabel.getEnd());
                    boolean phraseFound = checkPhrase(asSpan,
                            documentText.substring(asSpan.getBegin(), asSpan.getEnd()), subsetSize == 1, 0);
                    if (!phraseFound) {
                        int editedBegin = editedStringSpans.get(from).getBegin();
                        int editedEnd = editedStringSpans.get(from + last).getEnd();
                        String editedSubstring = editedString.substring(editedBegin, editedEnd);
                        phraseFound = checkPhrase(asSpan, editedSubstring, subsetSize == 1, .1);
                    }
                    if (!phraseFound) {
                        boolean lastTokenAllTrivial = partsOfSpeech.insideSpan(lastTokenLabel)
                                .all()
                                .stream()
                                .map(Label::value)
                                .allMatch(TRIVIAL_POS::contains);
                        if (!firstTokenAllTrivial && !lastTokenAllTrivial) {
                            checkTokenSet(windowSubset);
                        }
                    }
                }
            }
        }
    }
}
