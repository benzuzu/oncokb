package org.mskcc.cbio.oncokb.bo.impl;

import com.mysql.jdbc.StringUtils;
import org.mskcc.cbio.oncokb.bo.AlterationBo;
import org.mskcc.cbio.oncokb.bo.EvidenceBo;
import org.mskcc.cbio.oncokb.dao.AlterationDao;
import org.mskcc.cbio.oncokb.model.*;
import org.mskcc.cbio.oncokb.util.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.mskcc.cbio.oncokb.Constants.*;
import static org.mskcc.cbio.oncokb.model.InferredMutation.*;
import static org.mskcc.cbio.oncokb.model.StructuralAlteration.FUSIONS;
import static org.mskcc.cbio.oncokb.model.StructuralAlteration.TRUNCATING_MUTATIONS;
import static org.mskcc.cbio.oncokb.util.AlterationUtils.*;

/**
 * @author jgao
 */
public class AlterationBoImpl extends GenericBoImpl<Alteration, AlterationDao> implements AlterationBo {

    @Override
    public List<Alteration> findAlterationsByGene(Collection<Gene> genes) {
        List<Alteration> alterations = new ArrayList<Alteration>();
        for (Gene gene : genes) {
            alterations.addAll(getDao().findAlterationsByGene(gene));
        }
        return alterations;
    }

    private Alteration findExactlyMatchedAlteration(ReferenceGenome referenceGenome, Alteration alteration, Set<Alteration> fullAlterations) {
        Alteration matchedByAlteration = findAlteration(referenceGenome, alteration.getAlteration(), fullAlterations);
        if (matchedByAlteration != null) {
            if (matchedByAlteration.getConsequence() == null
                || alteration.getConsequence() == null
                || matchedByAlteration.getConsequence().getTerm().equalsIgnoreCase("NA")
                || alteration.getConsequence().getTerm().equalsIgnoreCase("NA")
            ) {
                return matchedByAlteration;
            }
            // We also want to do a consequence check, if the consequence has been specified, then it should be respected
            if (consequenceRelated(alteration.getConsequence(), matchedByAlteration.getConsequence())) {
                return matchedByAlteration;
            } else {
                return null;
            }
        } else {
            // For in-frame deletion, we should also look for variant with/out trailing amino acids
            VariantConsequence inframeDeletionConsequence = VariantConsequenceUtils.findVariantConsequenceByTerm(IN_FRAME_DELETION);
            if (inframeDeletionConsequence.equals(alteration.getConsequence())) {
                List<Alteration> matches = findMutationsByConsequenceAndPosition(alteration.getGene(), referenceGenome, inframeDeletionConsequence, alteration.getProteinStart(), alteration.getProteinEnd(), fullAlterations);
                if (matches.size() > 0) {
                    return matches.iterator().next();
                }
            }
        }

        return null;
    }

    private Alteration findAlteration(ReferenceGenome referenceGenome, String alteration, Set<Alteration> fullAlterations) {
        if (alteration == null) {
            return null;
        }
        // Implement the data access logic
        for (Alteration alt : fullAlterations) {
            if (alt.getAlteration() != null && alt.getAlteration().equalsIgnoreCase(alteration)) {
                if (referenceGenome == null) {
                    return alt;
                } else if (alt.getReferenceGenomes().contains(referenceGenome)) {
                    return alt;
                }
            }
        }
        for (Alteration alt : fullAlterations) {
            if (alt.getAlteration() != null && alt.getName().equalsIgnoreCase(alteration)) {
                if (referenceGenome == null) {
                    return alt;
                } else if (alt.getReferenceGenomes().contains(referenceGenome)) {
                    return alt;
                }
            }
        }

        if (NamingUtils.hasAbbreviation(alteration)) {
            return findAlteration(referenceGenome, NamingUtils.getFullName(alteration), fullAlterations);
        }
        return null;
    }

    private Alteration findAlteration(ReferenceGenome referenceGenome, String alteration, String name, Set<Alteration> fullAlterations) {
        if (alteration == null) {
            return null;
        }
        for (Alteration alt : fullAlterations) {
            if (alt.getAlteration() != null && alt.getAlteration().equalsIgnoreCase(alteration) && alt.getName().equalsIgnoreCase(name)) {
                if (referenceGenome == null) {
                    return alt;
                } else if (alt.getReferenceGenomes().contains(referenceGenome)) {
                    return alt;
                }
            }
        }
        return null;
    }

    @Override
    public Alteration findAlteration(Gene gene, AlterationType alterationType, String alteration) {
        return findAlteration(gene, alterationType, null, alteration);
    }

    @Override
    public Alteration findAlteration(Gene gene, AlterationType alterationType, ReferenceGenome referenceGenome, String alteration) {
        return findAlteration(referenceGenome, alteration, CacheUtils.getAlterations(gene.getEntrezGeneId()));
    }

    @Override
    public Alteration findAlteration(Gene gene, AlterationType alterationType, ReferenceGenome referenceGenome, String alteration, String name) {
        return findAlteration(referenceGenome, alteration, name, CacheUtils.getAlterations(gene.getEntrezGeneId()));
    }

    @Override
    public Alteration findAlterationFromDao(Gene gene, AlterationType alterationType, ReferenceGenome referenceGenome, String alteration, String name) {
        return getDao().findAlteration(gene, alterationType, referenceGenome, alteration, name);
    }

    @Override
    public List<Alteration> findMutationsByConsequenceAndPosition(Gene gene, ReferenceGenome referenceGenome, VariantConsequence consequence, int start, int end, Set<Alteration> alterations) {
        Set<Alteration> result = new HashSet<>();

        // Don't search for NA cases
        if (gene != null && consequence != null && !consequence.getTerm().equals("NA")) {
            if (alterations != null && alterations.size() > 0) {
                result.addAll(AlterationUtils.findOverlapAlteration(alterations, gene, referenceGenome, consequence, start, end));
            } else {
                Collection<Alteration> queryResult;
                queryResult = CacheUtils.findMutationsByConsequenceAndPosition(gene,referenceGenome, consequence, start, end);
                if (queryResult != null) {
                    result.addAll(queryResult);
                }
            }
        }

        List<Alteration> resultList = new ArrayList<>(result);
        AlterationUtils.sortAlterationsByTheRange(resultList, start, end);
        return resultList;
    }

    @Override
    public List<Alteration> findMutationsByConsequenceAndPositionOnSamePosition(Gene gene, ReferenceGenome referenceGenome, VariantConsequence consequence, int start, int end, String referenceResidue, Collection<Alteration> alterations) {
        Set<Alteration> result = new HashSet<>();

        if (alterations != null && alterations.size() > 0) {
            for (Alteration alteration : alterations) {
                if (alteration.getGene().equals(gene) && alteration.getConsequence() != null
                    && consequenceRelated(alteration.getConsequence(), consequence)
                    && alteration.getProteinStart() != null
                    && alteration.getProteinEnd() != null
                    && (referenceGenome == null || alteration.getReferenceGenomes().contains(referenceGenome))
                    && alteration.getProteinStart().equals(alteration.getProteinEnd())
                    && alteration.getProteinStart() >= start
                    && alteration.getProteinStart() <= end
                    && (alteration.getRefResidues() == null || referenceResidue == null || referenceResidue.equals(alteration.getRefResidues()))) {
                    result.add(alteration);
                }
            }
        } else {
            Collection<Alteration> queryResult;
            queryResult = CacheUtils.findMutationsByConsequenceAndPositionOnSamePosition(gene, referenceGenome, consequence, start, end, referenceResidue);
            if (queryResult != null) {
                result.addAll(queryResult);
            }
        }

        return new ArrayList<>(result);
    }

    /**
     * Find all relevant alterations. The order is important. The list should be generated based on priority.
     *
     * @param alteration
     * @return
     */
    @Override
    public LinkedHashSet<Alteration> findRelevantAlterations(ReferenceGenome referenceGenome, Alteration alteration, boolean includeAlternativeAllele) {
        return findRelevantAlterationsSub(referenceGenome, alteration, AlterationUtils.getAllAlterations(referenceGenome, alteration.getGene()), includeAlternativeAllele);
    }

    /**
     * Find all relevant alterations. The order is important. The list should be generated based on priority.
     *
     * @param alteration
     * @param fullAlterations
     * @return
     */
    @Override
    public LinkedHashSet<Alteration> findRelevantAlterations(ReferenceGenome referenceGenome, Alteration alteration, Set<Alteration> fullAlterations, boolean includeAlternativeAllele) {
        if (fullAlterations == null) {
            return new LinkedHashSet<>();
        }
        return findRelevantAlterationsSub(referenceGenome, alteration, fullAlterations, includeAlternativeAllele);
    }

    @Override
    public void deleteMutationsWithoutEvidenceAssociatedByGene(Gene gene) {
        EvidenceBo evidenceBo = ApplicationContextSingleton.getEvidenceBo();
        Set<Alteration> relatedAlts = new HashSet<>();
        List<Alteration> noMappingAlts = new ArrayList<>();
        for (Evidence evidence : evidenceBo.findEvidencesByGeneFromDB(Collections.singleton(gene))) {
            relatedAlts.addAll(evidence.getAlterations());
        }
        for (Alteration alteration : findAlterationsByGene(Collections.singleton(gene))) {
            if (!relatedAlts.contains(alteration)) {
                noMappingAlts.add(alteration);
            }
        }
        deleteAll(noMappingAlts);
    }

    private Set<Alteration> getRelevantAlterationsForMutationEffectMutations(ReferenceGenome referenceGenome, Gene gene, InferredMutation alteration) {
        String mutationEffect = alteration.getVariant().toLowerCase().replace("mutations", "").trim();
        return EvidenceUtils.getEvidenceByGeneAndEvidenceTypes(gene, Collections.singleton(EvidenceType.MUTATION_EFFECT)).stream().filter(evidence -> !StringUtils.isNullOrEmpty(evidence.getKnownEffect()) && mutationEffect.equals(evidence.getKnownEffect().toLowerCase().replace("likely", "").trim())).map(evidence -> evidence.getAlterations()).flatMap(Collection::stream).filter(alt -> alt.getReferenceGenomes().contains(referenceGenome)).collect(Collectors.toSet());
    }

    private Set<Alteration> getRelevantAlterationsForTruncatingMutations(ReferenceGenome referenceGenome, Set<Alteration> fullAlterations) {
        return fullAlterations.stream().filter(alt -> {
            VariantConsequence variantConsequence = alt.getConsequence();
            return variantConsequence != null && variantConsequence.getIsGenerallyTruncating() && alt.getReferenceGenomes().contains(referenceGenome);
        }).collect(Collectors.toSet());
    }

    private Set<Alteration> getRelevantAlterationsForFusions(ReferenceGenome referenceGenome, Set<Alteration> fullAlterations) {
        return fullAlterations.stream().filter(alt -> alt.getAlteration().toLowerCase().contains("fusion") && alt.getReferenceGenomes().contains(referenceGenome)).collect(Collectors.toSet());
    }

    private Set<Alteration> getRelevantAlterationsForOncogenicMutations(ReferenceGenome referenceGenome, Gene gene) {
        return EvidenceUtils.getEvidenceByGeneAndEvidenceTypes(gene, Collections.singleton(EvidenceType.ONCOGENIC)).stream().filter(evidence -> {
            Oncogenicity oncogenicity = Oncogenicity.getByEffect(evidence.getKnownEffect());
            return MainUtils.isOncogenic(oncogenicity);
        }).map(evidence -> evidence.getAlterations()).flatMap(Collection::stream).filter(alt -> alt.getReferenceGenomes().contains(referenceGenome)).collect(Collectors.toSet());
    }

    public LinkedHashSet<Alteration> findRelevantAlterationsForCategoricalAlt(ReferenceGenome referenceGenome, Alteration alteration, Set<Alteration> fullAlterations) {
        String altName = removeExclusionCriteria(alteration.getAlteration()).toLowerCase();
        LinkedHashSet<Alteration> relevant = new LinkedHashSet();

        if (altName.equals(ONCOGENIC_MUTATIONS.getVariant().toLowerCase())) {
            relevant = new LinkedHashSet<>(getRelevantAlterationsForOncogenicMutations(referenceGenome, alteration.getGene()));
        } else {
            List<InferredMutation> mutationEffectInferredAlterations = new ArrayList<>();
            mutationEffectInferredAlterations.add(GAIN_OF_FUNCTION_MUTATIONS);
            mutationEffectInferredAlterations.add(LOSS_OF_FUNCTION_MUTATIONS);
            mutationEffectInferredAlterations.add(SWITCH_OF_FUNCTION_MUTATIONS);
            Optional<InferredMutation> match = mutationEffectInferredAlterations.stream().filter(alt -> alt.getVariant().toLowerCase().equals(altName)).findFirst();
            if (match.isPresent()) {
                relevant = new LinkedHashSet<>(getRelevantAlterationsForMutationEffectMutations(referenceGenome, alteration.getGene(), match.get()));
            } else if (altName.equals(TRUNCATING_MUTATIONS.getVariant().toLowerCase())) {
                relevant = new LinkedHashSet<>(getRelevantAlterationsForTruncatingMutations(referenceGenome, fullAlterations));
            } else if (altName.equals(FUSIONS.getVariant().toLowerCase())) {
                relevant = new LinkedHashSet<>(getRelevantAlterationsForFusions(referenceGenome, fullAlterations));
            }
        }


        if (AlterationUtils.hasExclusionCriteria(alteration.getAlteration())) {
            Set<Alteration> altsShouldBeExcluded = AlterationUtils.getExclusionAlterations(alteration.getAlteration());
            altsShouldBeExcluded.forEach(alt -> {
                if (isPositionedAlteration(alt)) {
                    altsShouldBeExcluded.addAll(AlterationUtils.getAllMissenseAlleles(referenceGenome, alt.getProteinStart(), fullAlterations));
                }
            });
            Set<String> proteinChangesShouldBeExcluded = altsShouldBeExcluded.stream().map(alt -> alt.getAlteration().toLowerCase()).collect(Collectors.toSet());
            relevant = new LinkedHashSet<>(relevant.stream().filter(alt -> !proteinChangesShouldBeExcluded.contains(alt.getAlteration().toLowerCase())).collect(Collectors.toSet()));
        }

        return relevant;
    }
    /**
     * Find all relevant alterations. The order is important. The list should be generated based on priority.
     *
     * @param alteration
     * @param fullAlterations
     * @return
     */
    private LinkedHashSet<Alteration> findRelevantAlterationsSub(ReferenceGenome referenceGenome, Alteration alteration, Set<Alteration> fullAlterations, boolean includeAlternativeAllele) {
        LinkedHashSet<Alteration> alterations = new LinkedHashSet<>();
        Boolean addTruncatingMutations = false;
        Boolean addDeletion = false;

        // Alteration should always has consequence attached.
        if (alteration.getConsequence() == null) {
            AlterationUtils.annotateAlteration(alteration, alteration.getAlteration());
        }

        if (alteration.getConsequence().getTerm().equals("synonymous_variant")) {
            return alterations;
        }

        // Find exact match
        Alteration matchedAlt = findExactlyMatchedAlteration(referenceGenome, alteration, fullAlterations);

        if(matchedAlt == null && FusionUtils.isFusion(alteration.getAlteration())) {
            matchedAlt = AlterationUtils.getRevertFusions(referenceGenome, alteration, fullAlterations);
        }

        if (matchedAlt != null) {
            alteration = matchedAlt;
            alterations.add(matchedAlt);

            // check for oncogenicity, do not map any relevant alterations if the alteration has curated oncogenicity that not oncogenic
            Oncogenicity oncogenicity = MainUtils.getCuratedAlterationOncogenicity(matchedAlt);
            if (oncogenicity != null && !MainUtils.isOncogenic(oncogenicity)) {
                return alterations;
            }
        }


        if (addEGFRCTD(alteration)) {
            Alteration alt = findAlteration(referenceGenome, "CTD", fullAlterations);
            if (alt != null) {
                alterations.add(alt);
            }
        }

        if (alteration.getGene().getHugoSymbol().equals("EGFR") && alteration.getAlteration().equals("CTD")) {
            Alteration alt = findAlteration(referenceGenome, "CTD", fullAlterations);
            if (alt != null && !alterations.contains(alt)) {
                alterations.add(alt);
            }
        }

        // Find fusion variant
        //If alteration contains 'fusion' or alterationType is fusion
        if ((alteration.getAlteration() != null && alteration.getAlteration().toLowerCase().contains("fusion"))
            || (alteration.getAlterationType() != null && alteration.getAlterationType().equals(AlterationType.FUSION))
            || (alteration.getAlterationType() != null && alteration.getAlterationType().equals(AlterationType.STRUCTURAL_VARIANT)
            && alteration.getConsequence() != null && alteration.getConsequence().equals(VariantConsequenceUtils.findVariantConsequenceByTerm("fusion")))) {
            // TODO: match fusion partner

            //the alteration 'fusions' should be injected into alteration list
            List<Alteration> alts = findFusions(fullAlterations);
            if (!alts.isEmpty()) {
                alterations.addAll(alts);
            } else {
                // If no fusions curated, check the Truncating Mutations.
                addTruncatingMutations = true;
            }
        }

        //Find Alternative Alleles for missense variant
        if (alteration.getConsequence().equals(VariantConsequenceUtils.findVariantConsequenceByTerm(MISSENSE_VARIANT)) && !AlterationUtils.isPositionedAlteration(alteration)) {
            List<Alteration> includeRangeAlts = new ArrayList<>();

            if (includeAlternativeAllele) {
                alterations.addAll(AlterationUtils.getAlleleAlterations(referenceGenome, alteration, fullAlterations));
                // Include the range mutation
                List<Alteration> mutationsByConsequenceAndPosition = findMutationsByConsequenceAndPosition(alteration.getGene(), referenceGenome, alteration.getConsequence(), alteration.getProteinStart(), alteration.getProteinEnd(), fullAlterations);
                for (Alteration alt : mutationsByConsequenceAndPosition) {
                    if (!alt.getProteinStart().equals(alt.getProteinEnd())) {
                        if (alt.getRefResidues() != null && alteration.getRefResidues() != null && alt.getProteinStart() != null && alteration.getProteinStart() != null) {
                            int distance = Math.abs(alt.getProteinStart() - alteration.getProteinStart());
                            if (distance < alt.getRefResidues().length()) {
                                char rangeAltMatchedResidue = alt.getRefResidues().charAt(distance);
                                if (rangeAltMatchedResidue == alteration.getRefResidues().charAt(0)) {
                                    includeRangeAlts.add(alt);
                                }
                            }
                        } else {
                            includeRangeAlts.add(alt);
                        }
                    }
                }
            }

            // For missense mutation, also include positioned
            includeRangeAlts.addAll(AlterationUtils.getPositionedAlterations(referenceGenome, alteration, fullAlterations));

            for (Alteration alt : includeRangeAlts) {
                if (!alterations.contains(alt)) {
                    alterations.add(alt);
                }
            }
        } else {
            alterations.addAll(findMutationsByConsequenceAndPosition(alteration.getGene(),referenceGenome, alteration.getConsequence(), alteration.getProteinStart(), alteration.getProteinEnd(), fullAlterations));
        }


        if (alteration.getConsequence().getIsGenerallyTruncating()) {
            addTruncatingMutations = true;
        }else{
            // Match non_truncating_variant for non truncating variant
            VariantConsequence nonTruncatingVariant = VariantConsequenceUtils.findVariantConsequenceByTerm("non_truncating_variant");
            alterations.addAll(findMutationsByConsequenceAndPosition(alteration.getGene(),referenceGenome, nonTruncatingVariant, alteration.getProteinStart(), alteration.getProteinEnd(), fullAlterations));
        }

        // Match all variants with `any` as consequence. Currently, only format start_end mut is supported.
        VariantConsequence anyConsequence = VariantConsequenceUtils.findVariantConsequenceByTerm("any");
        alterations.addAll(findMutationsByConsequenceAndPosition(alteration.getGene(),referenceGenome, anyConsequence, alteration.getProteinStart(), alteration.getProteinEnd(), fullAlterations));

        // Remove all range mutations as relevant for truncating mutations in oncogenes
        alterations = oncogeneTruncMuts(alteration, alterations);

        // Match Truncating Mutations section to Deletion if no Deletion section specifically curated
        if (alteration.getAlteration().toLowerCase().matches("deletion")) {
            addDeletion = true;
            addTruncatingMutations = true;
        }

        if (addDeletion) {
            Alteration deletion = findAlteration(referenceGenome, "Deletion", fullAlterations);
            if (deletion != null) {
                alterations.add(deletion);

                // If there is Deletion annotated already, do not associate Truncating Mutations
                addTruncatingMutations = false;
            }
        }

        if (addTruncatingMutations) {
            VariantConsequence truncatingVariantConsequence = VariantConsequenceUtils.findVariantConsequenceByTerm("feature_truncation");
            alterations.addAll(findMutationsByConsequenceAndPosition(alteration.getGene(), referenceGenome, truncatingVariantConsequence, alteration.getProteinStart(), alteration.getProteinEnd(), fullAlterations));
        }

        if (addOncogenicMutations(alteration, alterations)) {
            List<Alteration> oncogenicMutations = findOncogenicMutations(fullAlterations);
            if (!oncogenicMutations.isEmpty()) {
                alterations.addAll(oncogenicMutations);
            }
        }

        // Looking for general biological effect variants. Gain-of-function mutations, Loss-of-function mutations etc.
        EvidenceBo evidenceBo = ApplicationContextSingleton.getEvidenceBo();
        List<Evidence> mutationEffectEvs = evidenceBo.findEvidencesByAlteration(alterations, Collections.singleton(EvidenceType.MUTATION_EFFECT));
        Set<String> effects = new HashSet<>();

        for (Evidence evidence : mutationEffectEvs) {
            String effect = evidence.getKnownEffect();
            if (effect != null) {
                effect = effect.toLowerCase();
                effect = effect.replaceAll("likely", "");
                effect = effect.replaceAll(" ", "");

                effects.add(effect);
            }
        }

        for (String effect : effects) {
            Alteration alt = findAlteration(referenceGenome, effect + " mutations", fullAlterations);
            if (alt != null) {
                alterations.add(alt);
            }
        }

        if (isEGFRSpecialVariant(alteration)) {
            Iterator<Alteration> iter = alterations.iterator();
            while (iter.hasNext()) {
                Alteration alt = iter.next();
                if (alt.getAlteration().equals("762_823ins")) {
                    iter.remove();
                }
            }
        }

        if (!addOncogenicMutations(alteration, alterations) && addVUSMutation(alteration, matchedAlt != null)) {
            Alteration VUSMutation = findAlteration(referenceGenome, InferredMutation.VUS.getVariant(), fullAlterations);
            if (VUSMutation != null) {
                alterations.add(VUSMutation);
            }
        }

        // Remove all relevant alterations that potentially excluding the current alteration
        Set<Alteration> exclusionAlts = new HashSet<>();
        Set<Alteration> relevantAlterationsWithoutAlternativeAlleles = alterations.stream().collect(Collectors.toSet());

        // when no matched alteration found in the database, we shuold include the `alteration` in the list to calculate the name
        if (matchedAlt == null) {
            relevantAlterationsWithoutAlternativeAlleles.add(alteration);
        }
        relevantAlterationsWithoutAlternativeAlleles.removeAll(AlterationUtils.getAlleleAlterations(referenceGenome, alteration, fullAlterations));
        Set<String> alterationsName = new HashSet<>();
        // if the alteration is inframe-ins/del, we should only match the alteration
        if(alteration.getConsequence() != null && (alteration.getConsequence().equals(VariantConsequenceUtils.findVariantConsequenceByTerm("inframe_deletion")) || alteration.getConsequence().equals(VariantConsequenceUtils.findVariantConsequenceByTerm("inframe_insertion")))) {
            alterationsName.add(alteration.getAlteration());
        } else {
            alterationsName.addAll(relevantAlterationsWithoutAlternativeAlleles.stream().map(Alteration::getAlteration).collect(Collectors.toSet()));
        }
        alterations.stream().filter(alt -> AlterationUtils.hasExclusionCriteria(alt.getAlteration()))
            .forEach(alt -> {
                Set<String> altsShouldBeExcluded = AlterationUtils.getExclusionAlterations(alt.getAlteration()).stream().map(alteration1->alteration1.getAlteration()).collect(Collectors.toSet());
                boolean altShouldBeExcluded = !Collections.disjoint(alterationsName, altsShouldBeExcluded);
                if (altShouldBeExcluded) {
                    exclusionAlts.add(alt);
                }
            });
        alterations.removeAll(exclusionAlts);
        return alterations;
    }

    private boolean isEGFRSpecialVariant(Alteration alteration) {
        return alteration != null && alteration.getGene().getHugoSymbol().equals("EGFR") && alteration.getAlteration().equals("A763_Y764insFQEA");
    }

    @Override
    public void save(Alteration alteration) {
        super.save(alteration);
        CacheUtils.forceUpdateGeneAlterations(alteration.getGene().getEntrezGeneId());
    }


    private boolean addEGFRCTD(Alteration exactAlt) {
        boolean add = false;
        if (exactAlt != null && exactAlt.getGene() != null
            && exactAlt.getGene().equals(GeneUtils.getGeneByHugoSymbol("EGFR"))
            && !StringUtils.isNullOrEmpty(exactAlt.getAlteration())
            && exactAlt.getAlteration().trim().matches("^vIV(a|b|c)?$")) {
            add = true;
        }
        return add;
    }

    private boolean addOncogenicMutations(Alteration exactAlt, Set<Alteration> relevantAlts) {
        boolean add = false;
            if (!exactAlt.getAlteration().trim().equalsIgnoreCase("amplification")) {
                Set<Oncogenicity> oncogenicities = AlterationUtils.getCuratedOncogenicity(exactAlt);
                boolean has = AlterationUtils.hasImportantCuratedOncogenicity(oncogenicities);
                if (has) {
                    Boolean isOncogenic = AlterationUtils.hasOncogenic(oncogenicities);

                    if (isOncogenic != null && isOncogenic) {
                        add = true;
                    }
                } else if (HotspotUtils.isHotspot(exactAlt)) {
                    add = true;
                } else {
                    // When we look at the oncogenicity, the VUS relevant variants should be excluded.
                    for (Alteration alt : AlterationUtils.excludeVUS(new ArrayList<>(relevantAlts))) {
                        Boolean isOncogenic = AlterationUtils.isOncogenicAlteration(alt);

                        if (isOncogenic != null && isOncogenic) {
                            add = true;
                            break;
                        }
                    }
                }
            }
        return add;
    }

    private boolean addVUSMutation(Alteration alteration, boolean alterationIsCurated){
        return !alterationIsCurated || AlterationUtils.getVUS(alteration).contains(alteration);
    }

    private LinkedHashSet<Alteration> oncogeneTruncMuts(Alteration alteration, LinkedHashSet<Alteration> relevantAlts) {
        if (alteration.getGene().getOncogene() != null && alteration.getGene().getTSG() != null && alteration.getGene().getOncogene() && !alteration.getGene().getTSG() && alteration.getConsequence().getIsGenerallyTruncating()) {
            LinkedHashSet<Alteration> filtered = new LinkedHashSet<>();
            for (Alteration alt : relevantAlts) {
                if (alt.getConsequence().getIsGenerallyTruncating() || alt.getProteinEnd().equals(alt.getProteinStart()) || alt.getProteinStart().equals(-1)) {
                    filtered.add(alt);
                }
            }
            return filtered;
        } else {
            return relevantAlts;
        }
    }
}
