package io.quarkiverse.operatorsdk.bundle.deployment;

import static io.quarkiverse.operatorsdk.deployment.AddClusterRolesDecorator.ALL_VERBS;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.logging.Logger;

import io.dekorate.utils.Serialization;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.quarkiverse.operatorsdk.bundle.runtime.BundleGenerationConfiguration;
import io.quarkiverse.operatorsdk.bundle.runtime.CSVMetadata;
import io.quarkiverse.operatorsdk.bundle.runtime.CSVMetadataHolder;
import io.quarkiverse.operatorsdk.bundle.runtime.SharedCSVMetadata;
import io.quarkiverse.operatorsdk.common.ClassUtils;
import io.quarkiverse.operatorsdk.common.ConfigurationUtils;
import io.quarkiverse.operatorsdk.deployment.ConfigurationServiceBuildItem;
import io.quarkiverse.operatorsdk.deployment.GeneratedCRDInfoBuildItem;
import io.quarkiverse.operatorsdk.runtime.BuildTimeOperatorConfiguration;
import io.quarkiverse.operatorsdk.runtime.ResourceInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.GeneratedFileSystemResourceBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.kubernetes.spi.GeneratedKubernetesResourceBuildItem;

public class BundleProcessor {
    private static final Logger log = Logger.getLogger(BundleProcessor.class);
    private static final DotName SHARED_CSV_METADATA = DotName.createSimple(SharedCSVMetadata.class.getName());
    private static final DotName CSV_METADATA = DotName.createSimple(CSVMetadata.class.getName());
    private static final String BUNDLE = "bundle";

    @SuppressWarnings({ "unchecked", "unused" })
    @BuildStep
    CSVMetadataBuildItem gatherCSVMetadata(ApplicationInfoBuildItem configuration,
            BundleGenerationConfiguration bundleConfiguration,
            CombinedIndexBuildItem combinedIndexBuildItem,
            ConfigurationServiceBuildItem configurations) {
        final var controllerConfigs = configurations.getControllerConfigs();
        final var index = combinedIndexBuildItem.getIndex();
        final var operatorCsvMetadata = getCSVMetadataForOperator(
                bundleConfiguration.packageName.orElse(configuration.getName()), index);
        final var csvGroups = new HashMap<CSVMetadataHolder, List<AugmentedResourceInfo>>();

        ClassUtils.getKnownReconcilers(index, log)
                .forEach(reconcilerInfo -> {
                    // figure out which group should be used to generate CSV
                    final var name = reconcilerInfo.name();
                    log.debugv("Processing reconciler: {0}", name);
                    final var config = controllerConfigs.get(name);
                    if (config == null) {
                        throw new IllegalStateException("Missing configuration for reconciler " + name);
                    }

                    // Check whether the reconciler must be shipped using a custom bundle
                    CSVMetadataHolder csvMetadata = getCsvMetadataFromAnnotation(operatorCsvMetadata,
                            reconcilerInfo.classInfo()).orElse(operatorCsvMetadata);
                    final var resourceFullName = config.getResourceTypeName();
                    final var resourceInfo = ResourceInfo.createFrom(config.getResourceClass(),
                            resourceFullName, name, config.isStatusPresentAndNotVoid());
                    csvGroups.computeIfAbsent(csvMetadata, m -> new ArrayList<>())
                            .add(new AugmentedResourceInfo(resourceInfo, csvMetadata.name));
                });

        return new CSVMetadataBuildItem(csvGroups);
    }

    @SuppressWarnings("unused")
    @BuildStep
    void generateBundle(ApplicationInfoBuildItem configuration,
            BundleGenerationConfiguration bundleConfiguration,
            BuildTimeOperatorConfiguration operatorConfiguration,
            OutputTargetBuildItem outputTarget,
            CSVMetadataBuildItem csvMetadata,
            BuildProducer<GeneratedBundleBuildItem> doneGeneratingCSV,
            GeneratedCRDInfoBuildItem generatedCustomResourcesDefinitions,
            List<GeneratedKubernetesResourceBuildItem> generatedKubernetesManifests,
            BuildProducer<GeneratedFileSystemResourceBuildItem> generatedCSVs) {
        if (bundleConfiguration.enabled) {
            try {
                final var crds = generatedCustomResourcesDefinitions.getCRDGenerationInfo().getCrds().values()
                        .stream().flatMap(entry -> entry.values().stream()).collect(Collectors.toList());
                final var outputDir = outputTarget.getOutputDirectory().resolve(BUNDLE);
                final var serviceAccounts = new LinkedList<ServiceAccount>();
                final var clusterRoleBindings = new LinkedList<ClusterRoleBinding>();
                final var clusterRoles = new LinkedList<ClusterRole>();
                final var roleBindings = new LinkedList<RoleBinding>();
                final var roles = new LinkedList<Role>();
                final var deployments = new LinkedList<Deployment>();

                generatedKubernetesManifests.stream()
                        .filter(bi -> bi.getName().equals("kubernetes.yml"))
                        .findAny()
                        .ifPresent(
                                bi -> {
                                    final var resources = Serialization
                                            .unmarshalAsList(new ByteArrayInputStream(bi.getContent()));
                                    resources.getItems().forEach(r -> {
                                        if (r instanceof ServiceAccount) {
                                            serviceAccounts.add((ServiceAccount) r);
                                            return;
                                        }

                                        if (r instanceof ClusterRoleBinding) {
                                            clusterRoleBindings.add((ClusterRoleBinding) r);
                                            return;
                                        }

                                        if (r instanceof ClusterRole) {
                                            clusterRoles.add((ClusterRole) r);
                                            return;
                                        }

                                        if (r instanceof RoleBinding) {
                                            roleBindings.add((RoleBinding) r);
                                            return;
                                        }

                                        if (r instanceof Role) {
                                            roles.add((Role) r);
                                            return;
                                        }

                                        if (r instanceof Deployment) {
                                            deployments.add((Deployment) r);
                                        }
                                    });
                                });
                final var generated = BundleGenerator.prepareGeneration(bundleConfiguration,
                        operatorConfiguration, csvMetadata.getCsvGroups(), crds);
                generated.forEach(manifestBuilder -> {
                    final var fileName = manifestBuilder.getFileName();
                    try {
                        generatedCSVs.produce(
                                new GeneratedFileSystemResourceBuildItem(
                                        Path.of(BUNDLE).resolve(manifestBuilder.getName()).resolve(fileName).toString(),
                                        manifestBuilder.getManifestData(serviceAccounts, clusterRoleBindings, clusterRoles,
                                                roleBindings, roles, deployments)));
                        log.infov("Generating {0} for {1} controller -> {2}",
                                manifestBuilder.getManifestType(),
                                manifestBuilder.getName(),
                                outputDir.resolve(manifestBuilder.getName()).resolve(fileName));
                    } catch (IOException e) {
                        log.errorv("Cannot generate {0} for {1}: {2}",
                                manifestBuilder.getManifestType(), manifestBuilder.getName(), e.getMessage());
                    }
                });
                doneGeneratingCSV.produce(new GeneratedBundleBuildItem());
            } catch (Exception e) {
                log.infov(e, "Couldn't generate bundle:");
            }
        }
    }

    private CSVMetadataHolder getCSVMetadataForOperator(String name, IndexView index) {
        CSVMetadataHolder csvMetadata = new CSVMetadataHolder(name);
        csvMetadata = aggregateMetadataFromSharedCsvMetadata(csvMetadata, index);
        return csvMetadata;
    }

    private Optional<CSVMetadataHolder> getCsvMetadataFromAnnotation(CSVMetadataHolder holder, ClassInfo info) {
        return Optional.ofNullable(info.classAnnotation(CSV_METADATA))
                .map(annotationInstance -> createMetadataHolder(annotationInstance, holder));
    }

    private CSVMetadataHolder aggregateMetadataFromSharedCsvMetadata(CSVMetadataHolder holder, IndexView index) {
        return index.getAllKnownImplementors(SHARED_CSV_METADATA).stream()
                .map(t -> t.classAnnotation(CSV_METADATA))
                .filter(Objects::nonNull)
                .map(annotation -> createMetadataHolder(annotation, holder))
                .findFirst()
                .orElse(holder);
    }

    private CSVMetadataHolder createMetadataHolder(AnnotationInstance csvMetadata, CSVMetadataHolder mh) {
        final var providerField = csvMetadata.value("provider");
        String providerName = null;
        String providerURL = null;
        if (providerField != null) {
            final var provider = providerField.asNested();
            providerName = ConfigurationUtils.annotationValueOrDefault(provider, "name",
                    AnnotationValue::asString, () -> mh.providerName);
            providerURL = ConfigurationUtils.annotationValueOrDefault(provider, "url",
                    AnnotationValue::asString, () -> mh.providerURL);
        }

        final var maintainersField = csvMetadata.value("maintainers");
        CSVMetadataHolder.Maintainer[] maintainers;
        if (maintainersField != null) {
            final var maintainersAnn = maintainersField.asNestedArray();
            maintainers = new CSVMetadataHolder.Maintainer[maintainersAnn.length];
            for (int i = 0; i < maintainersAnn.length; i++) {
                maintainers[i] = new CSVMetadataHolder.Maintainer(
                        ConfigurationUtils.annotationValueOrDefault(maintainersAnn[i], "name",
                                AnnotationValue::asString, () -> null),
                        ConfigurationUtils.annotationValueOrDefault(maintainersAnn[i], "email",
                                AnnotationValue::asString, () -> null));
            }
        } else {
            maintainers = mh.maintainers;
        }

        final var installModesField = csvMetadata.value("installModes");
        CSVMetadataHolder.InstallMode[] installModes;
        if (installModesField != null) {
            final var installModesAnn = installModesField.asNestedArray();
            installModes = new CSVMetadataHolder.InstallMode[installModesAnn.length];
            for (int i = 0; i < installModesAnn.length; i++) {
                installModes[i] = new CSVMetadataHolder.InstallMode(
                        ConfigurationUtils.annotationValueOrDefault(installModesAnn[i], "type",
                                AnnotationValue::asString, () -> null),
                        ConfigurationUtils.annotationValueOrDefault(installModesAnn[i], "supported",
                                AnnotationValue::asBoolean, () -> true));
            }
        } else {
            installModes = mh.installModes;
        }

        final var permissionsField = csvMetadata.value("permissionRules");
        CSVMetadataHolder.PermissionRule[] permissionRules;
        if (permissionsField != null) {
            final var permissionsAnn = permissionsField.asNestedArray();
            permissionRules = new CSVMetadataHolder.PermissionRule[permissionsAnn.length];
            for (int i = 0; i < permissionsAnn.length; i++) {
                permissionRules[i] = new CSVMetadataHolder.PermissionRule(
                        ConfigurationUtils.annotationValueOrDefault(permissionsAnn[i], "apiGroups",
                                AnnotationValue::asStringArray, () -> null),
                        ConfigurationUtils.annotationValueOrDefault(permissionsAnn[i], "resources",
                                AnnotationValue::asStringArray, () -> null),
                        ConfigurationUtils.annotationValueOrDefault(permissionsAnn[i], "verbs",
                                AnnotationValue::asStringArray, () -> ALL_VERBS),
                        ConfigurationUtils.annotationValueOrDefault(permissionsAnn[i], "serviceAccountName",
                                AnnotationValue::asString, () -> null));
            }
        } else {
            permissionRules = mh.permissionRules;
        }

        return new CSVMetadataHolder(
                ConfigurationUtils.annotationValueOrDefault(csvMetadata, "name",
                        AnnotationValue::asString, () -> mh.name),
                ConfigurationUtils.annotationValueOrDefault(csvMetadata, "description",
                        AnnotationValue::asString, () -> mh.description),
                ConfigurationUtils.annotationValueOrDefault(csvMetadata, "displayName",
                        AnnotationValue::asString, () -> mh.displayName),
                ConfigurationUtils.annotationValueOrDefault(csvMetadata, "keywords",
                        AnnotationValue::asStringArray, () -> mh.keywords),
                providerName, providerURL,
                ConfigurationUtils.annotationValueOrDefault(csvMetadata, "replaces",
                        AnnotationValue::asString, () -> mh.replaces),
                ConfigurationUtils.annotationValueOrDefault(csvMetadata, "version",
                        AnnotationValue::asString, () -> mh.version),
                ConfigurationUtils.annotationValueOrDefault(csvMetadata, "maturity",
                        AnnotationValue::asString, () -> mh.maturity),
                maintainers,
                installModes,
                permissionRules);
    }
}
