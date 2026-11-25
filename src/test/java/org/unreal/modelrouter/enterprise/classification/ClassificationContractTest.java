/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.enterprise.noop.CommunityClassificationRoutingPolicy;
import org.unreal.modelrouter.enterprise.noop.CommunityDataResidencyResolver;
import org.unreal.modelrouter.enterprise.spi.ClassificationContextHolder;
import org.unreal.modelrouter.enterprise.spi.ClassificationRoutingPolicy;
import org.unreal.modelrouter.enterprise.spi.DataClassification;
import org.unreal.modelrouter.enterprise.spi.DataResidencyResolver;
import org.unreal.modelrouter.router.model.ModelRouterProperties.ModelInstance;

/**
 * #9 数据分级出境契约测试：社区版零行为 + 三档密级 + fail-closed 语汇。
 *
 * @author Trunkline Team
 */
class ClassificationContractTest {

    @Test
    void communityPolicyAlwaysAllows() {
        final ClassificationRoutingPolicy policy = new CommunityClassificationRoutingPolicy();
        final ModelInstance instance = new ModelInstance();
        instance.setInstanceId("i-1");
        final ClassificationRoutingPolicy.PolicyDecision decision =
                policy.decide(DataClassification.CONFIDENTIAL, List.of(instance),
                        new CommunityDataResidencyResolver());
        assertNotNull(decision);
        assertEquals(ClassificationRoutingPolicy.Verdict.ALLOW, decision.verdict());
        assertTrue(decision.isValid());
    }

    @Test
    void communityResolverReadsRegionTag() {
        final ModelInstance instance = new ModelInstance();
        instance.setInstanceId("i-1");
        instance.setTags(java.util.Map.of("region", "domestic"));
        final DataResidencyResolver.InstanceResidencyView view =
                new CommunityDataResidencyResolver().resolve(instance);
        assertEquals("domestic", view.region());
        assertTrue(view.isEffective());
        assertTrue(view.isDomesticOrPrivate());
    }

    @Test
    void communityResolverWithoutRegionIsNotEffective() {
        final ModelInstance instance = new ModelInstance();
        instance.setInstanceId("i-2");
        final DataResidencyResolver.InstanceResidencyView view =
                new CommunityDataResidencyResolver().resolve(instance);
        assertFalse(view.isEffective());
    }

    @Test
    void classificationLookupIsStableAndCaseInsensitive() {
        assertEquals(DataClassification.PUBLIC, DataClassification.fromCode(null));
        assertEquals(DataClassification.PUBLIC, DataClassification.fromCode(""));
        assertEquals(DataClassification.CONFIDENTIAL, DataClassification.fromCode("confidential"));
        assertEquals(DataClassification.INTERNAL, DataClassification.fromCode(" Internal "));
        assertEquals(DataClassification.PUBLIC, DataClassification.fromCode("UNKNOWN"));
        assertTrue(DataClassification.CONFIDENTIAL.getPriority() > DataClassification.INTERNAL.getPriority());
    }

    @Test
    void contextHolderDefaultsToPublicAndClears() {
        ClassificationContextHolder.clear();
        assertEquals(DataClassification.PUBLIC, ClassificationContextHolder.get());
        ClassificationContextHolder.set(DataClassification.CONFIDENTIAL);
        assertEquals(DataClassification.CONFIDENTIAL, ClassificationContextHolder.get());
        ClassificationContextHolder.clear();
        assertEquals(DataClassification.PUBLIC, ClassificationContextHolder.get());
    }

    @Test
    void invalidPolicyDecisionIsRejectedByContract() {
        final ClassificationRoutingPolicy.PolicyDecision rerouteEmpty =
                ClassificationRoutingPolicy.PolicyDecision.reroute(List.of(), "p", "no instances");
        assertFalse(rerouteEmpty.isValid());
        final ClassificationRoutingPolicy.PolicyDecision deny =
                ClassificationRoutingPolicy.PolicyDecision.deny("p", "egress denied");
        assertTrue(deny.isValid());
        assertEquals(ClassificationRoutingPolicy.Verdict.DENY, deny.verdict());
    }
}
