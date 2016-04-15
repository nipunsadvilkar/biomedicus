package edu.umn.biomedicus.application;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import edu.umn.biomedicus.common.text.Document;
import edu.umn.biomedicus.exc.BiomedicusException;

import java.util.HashMap;
import java.util.Map;

/**
 *
 */
public class CollectionProcessorRunner {
    private final Injector injector;
    private final SettingsTransformer settingsTransformer;

    private Map<String, Object> globalSettings;

    private Map<Key<?>, Object> seededObjects;

    private CollectionProcessor collectionProcessor;

    private Class<? extends CollectionProcessor> collectionProcessorClass;

    private Injector settingsInjector;

    @Inject
    public CollectionProcessorRunner(Injector injector) {
        this.injector = injector;
        settingsTransformer = injector.getInstance(SettingsTransformer.class);
        settingsTransformer.setAnnotationFunction(ProcessorSettingImpl::new);
        globalSettings = injector.getInstance(Key.get(new TypeLiteral<Map<String, Object>>() {}, Names.named("globalSettings")));
    }

    public void setCollectionProcessorClass(Class<CollectionProcessor> collectionProcessorClass) {
        this.collectionProcessorClass = collectionProcessorClass;
    }

    public void setCollectionProcessorClassName(String collectionProcessorClassName) throws ClassNotFoundException {
        collectionProcessorClass = Class.forName(collectionProcessorClassName).asSubclass(CollectionProcessor.class);
    }

    public void initialize(Map<String, Object> collectionProcessorSettings) throws BiomedicusException {
        if (collectionProcessorClass == null) {
            throw new IllegalStateException("Collection processor class needs to be set");
        }

        settingsTransformer.addAll(collectionProcessorSettings);
        settingsTransformer.addAll(globalSettings);

        seededObjects = settingsTransformer.getSettings();
        settingsInjector = injector.createChildInjector(new ProcessorSettingsModule(seededObjects.keySet()));

        try {
            BiomedicusScopes.runInProcessorScope(() -> {
                collectionProcessor = settingsInjector.getInstance(collectionProcessorClass);
                return null;
            }, seededObjects);
        } catch (Exception e) {
            throw new BiomedicusException(e);
        }

    }

    public void processDocument(Document document) throws BiomedicusException {
        try {
            BiomedicusScopes.runInProcessorScope(() -> {
                collectionProcessor.processDocument(document);
                return null;
            }, seededObjects);
        } catch (Exception e) {
            throw new BiomedicusException(e);
        }
    }

    public void finish() throws BiomedicusException {
        try {
            BiomedicusScopes.runInProcessorScope(() -> {
                collectionProcessor.allDocumentsProcessed();
                return null;
            }, seededObjects);
        } catch (Exception e) {
            throw new BiomedicusException(e);
        }
    }
}