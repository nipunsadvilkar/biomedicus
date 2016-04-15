package edu.umn.biomedicus.acronym;

import com.google.inject.Inject;
import com.google.inject.ProvidedBy;
import com.google.inject.Singleton;
import edu.umn.biomedicus.annotations.Setting;
import edu.umn.biomedicus.application.DataLoader;
import edu.umn.biomedicus.exc.BiomedicusException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 *
 */
@ProvidedBy(AcronymExpansionsModel.Loader.class)
class AcronymExpansionsModel {
    private static final Logger LOGGER = LogManager.getLogger();

    private final Map<String, Collection<String>> expansions;

    private AcronymExpansionsModel(Map<String, Collection<String>> expansions) {
        this.expansions = expansions;
    }

    Collection<String> getExpansions(String acronym) {
        return expansions.get(acronym);
    }

    boolean hasExpansions(String acronym) {
        return expansions.containsKey(acronym);
    }

    @Singleton
    static class Loader extends DataLoader<AcronymExpansionsModel> {

        private final Path expansionsModelPath;

        @Inject
        Loader(@Setting("acronym.expansionsModel.path") Path expansionsModelPath) {
            this.expansionsModelPath = expansionsModelPath;
        }

        @Override
        protected AcronymExpansionsModel loadModel() throws BiomedicusException {
            LOGGER.info("Loading acronym expansions: {}", expansionsModelPath);
            Map<String, Collection<String>> expansions = new HashMap<>();
            Pattern splitter = Pattern.compile("\\|");
            try (BufferedReader bufferedReader = Files.newBufferedReader(expansionsModelPath)) {
                String acronym;
                while ((acronym = bufferedReader.readLine()) != null) {
                    String[] acronymExpansions = splitter.split(bufferedReader.readLine());
                    expansions.put(acronym, Arrays.asList(acronymExpansions));
                }
            } catch (IOException e) {
                throw new BiomedicusException(e);
            }

            return new AcronymExpansionsModel(expansions);
        }
    }
}