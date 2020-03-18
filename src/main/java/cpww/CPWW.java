package cpww;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;

import java.io.*;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;

import static cpww.Util.*;

public class CPWW {
    private static String data_name;
    private static String inputFolder;
    private static String outputFolder;
    private static int noOfLines;
    private static int minimumSupport;
    private static int maxLength;
    private static StanfordCoreNLP pipeline;
    private static boolean load_sentenceBreakdownData;
    private static boolean load_metapatternData;
    private static int noOfPushUps;

    private static String[] nerTypes;
    private static List<String> stopWords;
    private static List<SentenceProcessor> sentenceCollector;
    private static Map<String, String> entityDictionary = new HashMap<>();
    private static List<MetaPattern> frequentPatterns = new ArrayList<>();
    private static Map<String, List<MetaPattern>> singlePattern = new HashMap<>();
    private static Map<String, List<MetaPattern>> allPattern = new HashMap<>();
    private static Map<String, List<MetaPattern>> multiPattern = new HashMap<>();
    private static Map<Integer, List<String>> allPattern_Index = new HashMap<>();
    private static Map<String, Set<Integer>> allPattern_reverseIndex = new HashMap<>();

    private static Logger logger = Logger.getLogger(CPWW.class.getName()) ;


    public CPWW()  {
    }

    private static void initialization() throws IOException {
        Properties props = new Properties();
        props.setProperty("annotators", "tokenize,ssplit,pos,lemma,parse,depparse");
        pipeline = new StanfordCoreNLP(props);

        InputStream input = new FileInputStream("config.properties");
        Properties prop = new Properties();
        prop.load(input);
        if (!prop.containsKey("data_name") || prop.getProperty("data_name").equals("")) {
            throw new IOException("Parameter 'data_name' <does not exist/ is empty> in config.properties");
        }
        setOptionalParameterDefaults(prop);
        data_name = prop.getProperty("data_name");
        inputFolder = folderNameConsistency(prop.getProperty("inputFolder"));
        outputFolder = folderNameConsistency(prop.getProperty("outputFolder"));
        noOfLines = Integer.parseInt(prop.getProperty("noOfLines"));
        minimumSupport = Integer.parseInt(prop.getProperty("minimumSupport"));
        maxLength = Integer.parseInt(prop.getProperty("maxLength"));
        nerTypes = readList(new FileReader(prop.getProperty("nerTypes"))).toArray(new String[0]);
        stopWords = readList(new FileReader(prop.getProperty("stopWordsFile")));
        load_sentenceBreakdownData = Boolean.parseBoolean(prop.getProperty("load_sentenceBreakdownData"));
        load_metapatternData = Boolean.parseBoolean(prop.getProperty("load_metapatternData"));
        noOfPushUps = Integer.parseInt(prop.getProperty("noOfPushUps"));

        sentenceCollector = new ArrayList<>();
        FileHandler logFile = new FileHandler(outputFolder + data_name + "_logFile.txt");
        logFile.setFormatter(new SimpleFormatter());
        logger.addHandler(logFile);

        File dir = new File(inputFolder);
        if (!dir.exists()) {
            throw new IOException("Input Folder not found.");
        }
        dir = new File(outputFolder);
        if (!dir.exists()) {
            dir.mkdir();
        }
    }

//    public String replacedNER(String sentence) {
//        String[] arr = sentence.replaceAll("\n","").split(" ");
//        if (arr.length <5 || !Character.isLetter(sentence.charAt(0))) {
//            return null;
//        }
//        for (int i = 0; i < arr.length; i++) {
//            String orig = arr[i].replaceAll("([A-Z]+_[A-Z0-9)]+_[\\w']+(._)*[\\w']*)\\s*", "$1");
//            Pattern pattern = Pattern.compile("_[\\w']+(._)*[\\w']*");
//            Matcher matcher = pattern.matcher(orig);
//            arr[i] = orig.replaceAll("([A-Z]+)_[A-Z0-9)]+_[\\w']+(._)*[\\w']*","$1");
//
//            for (String ner : nerTypes) {
//                if (arr[i].contains(ner.toUpperCase())) {
//                    arr[i] = arr[i].replaceAll(ner.toUpperCase(), ner.toUpperCase() + (++nerCount));
//                    if (matcher.find()) {
//                        String match = matcher.group().replaceFirst("_[A-Z0-9]+_","");
//                        this.entityDictionary.put(ner.toUpperCase() + nerCount, match);
//                    }
//                    break;
//                }
//            }
//        }
//        return String.join(" ", arr).replaceAll("(_)+", "-");
//    }

    private static void frequent_pattern_mining(int iteration) throws  IOException{
        frequentPatterns.clear();

        List<SubSentWords> tokens = new ArrayList<>();
        Map<String, List<Integer>> dict_token = new HashMap<>();
        Map<String, List<Integer>> valid_pattern = new HashMap<>();
        int tokenNumber = 0;
        String token;
        for (SentenceProcessor sentence : sentenceCollector) {
            Map<SubSentWords, List<SubSentWords>> sentBreak = sentence.getSentenceBreakdown();
            for (SubSentWords key : sentBreak.keySet()) {
                List<SubSentWords> list = sentBreak.get(key);
                for (SubSentWords word : list) {
                    token = word.getLemma();
                    tokens.add(word);
                    updateMapCount(dict_token, token, tokenNumber++);
                    updateMapCount(valid_pattern, token, 1);
                }
                tokens.add(new SubSentWords("$","$", "", -1));
                tokenNumber++;
            }
            for (SubSentWords key : sentence.getPushedUpSentences().keySet()) {
                for (SubSentWords word : sentence.getPushedUpSentences().get(key)) {
                    token = word.getLemma();
                    tokens.add(word);
                    updateMapCount(dict_token, token, tokenNumber++);
                    updateMapCount(valid_pattern, token, 1);
                }
                tokens.add(new SubSentWords("$","$", "", -1));
                tokenNumber++;
            }
        }

        int patternLength = 1;
        while(dict_token.size() > 1) {
            if (patternLength > maxLength) break;
            patternLength++;
            Map<String, List<Integer>> newdict_token = new HashMap<>();
            Map<String, List<Integer>> newvalid_pattern = new HashMap<>();
            for (String pattern: dict_token.keySet()){
                List<Integer> positions = dict_token.get(pattern);
                List<Integer> valid = valid_pattern.get(pattern);
                int frequency = positions.size();
                if (frequency >= minimumSupport){
                    if (patternLength > 2 && sum(valid) >= minimumSupport){
                        MetaPattern metaPattern = new MetaPattern(pattern, nerTypes, stopWords, frequency);
                        if (metaPattern.isValid()) {
                            frequentPatterns.add(metaPattern);
                        }
                    }

                    for (Integer i : positions){
                        if (i + 1 < tokenNumber){
                            SubSentWords nextToken = tokens.get(i + 1);
                            if (nextToken.getLemma().equals("$")) continue;
                            String newPattern = pattern + " " + nextToken.getLemma();
                            int new_valid = 1;

                            List<String> pattern_tree = new ArrayList<>();
                            for (int j = i - patternLength + 2; j < i + 2; j++){
                                pattern_tree.add(tokens.get(j).getTrimmedEncoding());
                            }
                            String pattern_root = min_characters(pattern_tree);
                            for (String t : pattern_tree) {
                                if (!pattern_tree.contains(t.substring(0,t.length()-1)) && !t.equals(pattern_root)){
                                    new_valid = 0;
                                    break;
                                }
                            }
                            updateMapCount(newdict_token, newPattern, i + 1);
                            updateMapCount(newvalid_pattern, newPattern, new_valid);
                        }
                    }
                }
            }
            dict_token = new HashMap<>(newdict_token);
            valid_pattern = new HashMap<>(newvalid_pattern);
        }

        logger.log(Level.INFO, "COMPLETED: Frequent Pattern Mining - Iteration " + iteration);
        pattern_classification();
    }

    private static void pattern_classification() throws IOException {
        clearPatterns();
        int allPatternCount = 0;

        for (MetaPattern metaPattern : frequentPatterns.stream().sorted(
                Comparator.comparingInt(MetaPattern::getFrequency).reversed()).collect(Collectors.toList())) {
            String main_pattern = metaPattern.getClippedMetaPattern();
            String[] splitPattern = main_pattern.split(" ");
            List<MetaPattern> temp;
            if (metaPattern.getNerCount() == 1) {
                temp = singlePattern.getOrDefault(main_pattern, new ArrayList<>());
                temp.add(metaPattern);
                singlePattern.put(main_pattern, temp);
            } else if (metaPattern.getNerCount() > 1) {
                temp = multiPattern.getOrDefault(main_pattern, new ArrayList<>());
                temp.add(metaPattern);
                multiPattern.put(main_pattern, temp);
            }
            if (metaPattern.getNerCount() >= 1) {
                temp = allPattern.getOrDefault(main_pattern, new ArrayList<>());
                temp.add(metaPattern);
                allPattern.put(main_pattern, temp);
                allPatternCount++;
                allPattern_Index.put(allPatternCount,new ArrayList<>(Arrays.asList(splitPattern)));
                for (String word: splitPattern){
                    Set<Integer> indices = new HashSet<>(allPattern_reverseIndex.getOrDefault(word, new HashSet<>()));
                    indices.add(allPatternCount);
                    allPattern_reverseIndex.put(word,indices);
                }
            }
        }
    }

    private static void clearPatterns() {
        singlePattern.clear();
        allPattern.clear();
        multiPattern.clear();
        allPattern_Index.clear();
        allPattern_reverseIndex.clear();
    }

    /**
     *
     * @param sentenceLemma -> Lemma words of the pushed up/ original subsentence being looked at
     * @param sentenceEncode -> Encoding of the original subsentence
     * @param currentNode -> index of the current word being looked at
     * @param cand_set -> Set of indices of all patterns having prevNode word
     * @param wordBagTemp -> Map of current status of pattern words being crossed out
     * @return if atleast one pattern is matched or not
     */
    private static boolean treeSearch(List<String> sentenceLemma, List<String> sentenceEncode, int currentNode,
                                      int prevNode, int entity, Set<Integer> cand_set, Map<Integer, List<String>> wordBagTemp){
        Set<Integer> candidateSet = new HashSet<>(cand_set);
        Map<Integer, List<String>> wordBag_temp = new HashMap<>(wordBagTemp);
        if (currentNode != entity) {
            if (!allPattern_reverseIndex.containsKey(sentenceLemma.get(currentNode))) return false;
            candidateSet.retainAll(new HashSet<>(allPattern_reverseIndex.get(sentenceLemma.get(currentNode))));
            if (candidateSet.isEmpty()) {
                return false;
            }
            for (Integer index : candidateSet) {
                if (wordBag_temp.containsKey(index)) {
                    List<String> temp = new ArrayList<>(wordBag_temp.get(index));
                    temp.remove(sentenceLemma.get(currentNode));
                    wordBag_temp.put(index, temp);
                }
                if (wordBag_temp.get(index).size() == 0) {
                    return true;
                }
            }
        }
        boolean validPushUpScenario;
        for (int i = 65; i < 91; i++) {
            String ch = sentenceEncode.get(currentNode) + (char)i;
            int child = returnEncodeIndex(sentenceEncode, ch);
            if (child != -1 && (prevNode == entity || child != prevNode)) {
                validPushUpScenario = treeSearch(sentenceLemma, sentenceEncode, child, currentNode, entity, candidateSet, wordBag_temp);
                if (validPushUpScenario) {
                    return true;
                }
            }
        }
        String currentWordEncoding = sentenceEncode.get(currentNode);
        if (currentWordEncoding.length() < sentenceEncode.get(prevNode).length()) {
            int parent = returnEncodeIndex(sentenceEncode, currentWordEncoding.substring(0, currentWordEncoding.length() - 1));
            if (parent != -1) {
                return treeSearch(sentenceLemma, sentenceEncode, parent, currentNode, entity, candidateSet, wordBag_temp);
            }
        }
        return false;
    }

    private static SubSentWords identify_entityLeaves(SubSentWords rootExp, SentenceProcessor wholeSentence){
        String root = rootExp.getLemma();
        List<String> sentenceLemma = wholeSentence.getPushedUpSentences().getOrDefault(rootExp, wholeSentence.
                getSentenceBreakdown().get(rootExp)).stream().map(SubSentWords::getLemma).collect(Collectors.toList());
        List<String> sentenceEncode = wholeSentence.getSentenceBreakdown().get(rootExp).stream().
                map(SubSentWords::getTrimmedEncoding).collect(Collectors.toList());
        if (!allPattern_reverseIndex.containsKey(root)) return null;
        Set<Integer> root_index = new HashSet<>(allPattern_reverseIndex.get(root));
        List<String> typeSet = new ArrayList<>();
        List<Integer> specific_entities = new ArrayList<>();
        for (int i = 0; i < sentenceLemma.size(); i++){
            String word = sentenceLemma.get(i);
            for (String ner : nerTypes){
                if (word.contains(ner) && allPattern_reverseIndex.containsKey(word)){
                    Set<Integer> entity_index = new HashSet<>(allPattern_reverseIndex.get(word));
                    entity_index.retainAll(root_index);
                    if (entity_index.size() > 0) {
                        typeSet.add(ner);
                        specific_entities.add(i);
                    }
                    // If number of entities in subsentence are greater than 1, skip the identification
                    if (typeSet.size() > 1) {
                        return null;
                    }
                }
            }
        }

        if (typeSet.isEmpty()) {
            return null;
        }

        List<Integer> entities = new ArrayList<>();
        int longest_encode = 0;
        for (int i : specific_entities){
            String subEnc = sentenceEncode.get(i);
            if (subEnc.length() > longest_encode){
                entities.clear();
                entities.add(i);
                longest_encode = subEnc.length();
            } else if(subEnc.length() == longest_encode){
                entities.add(i);
            }
        }

        List<SubSentWords> merge_ent_encode = new ArrayList<>();
        for (Integer entity : entities){
            Set<Integer> candidate_set = new HashSet<>(allPattern_reverseIndex.get(sentenceLemma.get(entity)));
            Map<Integer, List<String>> wordBag_temp = new HashMap<>(allPattern_Index);
            for (Integer index : candidate_set){
                if (wordBag_temp.containsKey(index)){
                    List<String> temp = new ArrayList<>(wordBag_temp.get(index));
                    temp.remove(sentenceLemma.get(entity));
                    wordBag_temp.put(index,temp);
                }
            }
            boolean match_set = false;
            String currentWordEncoding = sentenceEncode.get(entity);
            int parent = returnEncodeIndex(sentenceEncode, currentWordEncoding.substring(0, currentWordEncoding.length()-1));
            if (parent != -1) {
                match_set = treeSearch(sentenceLemma, sentenceEncode, parent, entity, entity, candidate_set, wordBag_temp);
            }
            if (match_set) {
                if (merge_ent_encode.size() == 1) {
                    return null;
                }
                merge_ent_encode.add(wholeSentence.getPushedUpSentences().getOrDefault(rootExp,
                        wholeSentence.getSentenceBreakdown().get(rootExp)).get(entity));
            }
        }
        return merge_ent_encode.size() == 1 ? merge_ent_encode.get(0) : null;
    }

    private static void buildSentences() throws Exception {
        logger.log(Level.INFO, "STARTING: Sentences Processing");
        String inputFile = inputFolder + data_name + "_annotated.txt";
        BufferedReader reader = new BufferedReader(new FileReader(inputFile));
        String line = reader.readLine();
        boolean indexGiven = false;
        int lineNo = 1;
        if (line != null && line.split("\t").length != 1) {
            indexGiven = true;
        }
        while (line != null) {
            if (noOfLines > 0 && lineNo == noOfLines + 1) {
                break;
            }
            String sentence = indexGiven ? line.split("\t")[1] : line;
            String index = indexGiven ? line.split("\t")[0] : String.valueOf(lineNo);

            if (sentence != null && sentence.split(" ").length < 100) {
                SentenceProcessor sp = new SentenceProcessor(pipeline, nerTypes, entityDictionary, sentence, index);
                sentenceCollector.add(sp);
            }
            line = reader.readLine();
            lineNo++;
        }
        reader.close();
        logger.log(Level.INFO, "COMPLETED: Sentences Processing");
    }

    private static void buildDictionary() throws Exception {
        logger.log(Level.INFO, "STARTING: Dictionary Building");
        ObjectMapper mapper = new ObjectMapper();
        entityDictionary = mapper.readValue(new File(
                    inputFolder + data_name + "_dict.json"), new TypeReference<Map<String, String>>() {});
        logger.log(Level.INFO, "COMPLETED: Dictionary Building");
    }

    private static void saveSentenceBreakdown() throws Exception {
        logger.log(Level.INFO, "STARTING: Sentence Breakdown Serialization");
        String directory = inputFolder + data_name;
        FileOutputStream fileOut = new FileOutputStream(directory + "_sentenceBreakdown." + noOfLines + ".txt");
        ObjectOutputStream out = new ObjectOutputStream(fileOut);
        out.writeObject(sentenceCollector);
        fileOut.close();
        logger.log(Level.INFO, "COMPLETED: Sentence Breakdown Serialization");
    }

    private static void loadSentenceBreakdown() throws Exception {
        logger.log(Level.INFO, "STARTING: Sentence Breakdown Deserialization");
        String directory = inputFolder + data_name;
        FileInputStream fileIn = new FileInputStream(directory + "_sentenceBreakdown." + noOfLines + ".txt");
        ObjectInputStream in = new ObjectInputStream(fileIn);
        sentenceCollector = (List<SentenceProcessor>) in.readObject();
        fileIn.close();
        logger.log(Level.INFO, "COMPLETED: Sentence Breakdown Deserialization");
    }

    private static void savePatternClassificationData() throws IOException{
        logger.log(Level.INFO, "STARTING: Saving Meta-Pattern Classification Data");
        String directory = outputFolder + data_name;
        String suffix = "." + noOfLines + "_" + minimumSupport + ".txt";
        FileWriter singlePatterns = new FileWriter(directory + "_singlePatterns" + suffix);
        FileWriter multiPatterns = new FileWriter(directory + "_multiPatterns" + suffix);
        FileWriter allPatterns = new FileWriter(directory + "_allPatterns" + suffix);
        writePatternsToFile(new BufferedWriter(singlePatterns), singlePattern);
        writePatternsToFile(new BufferedWriter(multiPatterns), multiPattern);
        writePatternsToFile(new BufferedWriter(allPatterns), allPattern);
        logger.log(Level.INFO, "COMPLETED: Saving Meta-Pattern Classification Data");
    }

    private static void loadPatternClassificationData() throws IOException {
        String name = "_allPatterns";
        String directory = outputFolder + data_name;
        String suffix = "." + noOfLines + "_" + minimumSupport + ".txt";
        BufferedReader patterns = new BufferedReader(new FileReader(directory + name + suffix));
        String line = patterns.readLine();
        frequentPatterns = new ArrayList<>();
        while (line != null) {
            String[] data = line.split("->");
            frequentPatterns.add(new MetaPattern(data[0], nerTypes, stopWords, Integer.parseInt(data[1])));
            line = patterns.readLine();
        }
        pattern_classification();
    }

    private static void hierarchicalPushUp(SentenceProcessor sentence) {
        sentence.resetPushUp();
        List<SubSentWords> sortedSubKeys = new ArrayList<>(sort_leafToTop(sentence));
        for (SubSentWords sub : sortedSubKeys) {
            boolean termReplaced = false;
            List<SubSentWords> subWords = new ArrayList<>(sentence.getSentenceBreakdown().get(sub));
            if (!sentence.getReplaceSurfaceName().isEmpty()) {
                for (int i = 0; i < subWords.size(); i++) {
                    String encode = subWords.get(i).getTrimmedEncoding();
                    if (sentence.getReplaceSurfaceName().containsKey(encode)) {
                        termReplaced = true;
                        subWords.set(i, sentence.getReplaceSurfaceName().get(encode));
                    }
                }
            }
            if (termReplaced) {
                sentence.pushUpSentences(sub, subWords);
            }
            SubSentWords entityLeaf = identify_entityLeaves(sub, sentence);
            if (entityLeaf != null) {
                sentence.updateReplacedSurfaceName(sub.getTrimmedEncoding(), entityLeaf);
            }
        }
    }

    private static List<String> patternFinding(SentenceProcessor sentence, List<Map<String, Integer>> patternList) {
        List<String> ans = new ArrayList<>();
        String output;
        Map<SubSentWords, List<SubSentWords>> map;
        for (SubSentWords subRoot : sentence.getSentenceBreakdown().keySet()) {
            int iter = 1;
            if (sentence.getPushedUpSentences().containsKey(subRoot)) {
                iter++;
            }
            while (iter > 0) {
                map = (iter == 2) ? sentence.getPushedUpSentences() : sentence.getSentenceBreakdown();
                output = patternMatchingHelper(sentence, map, subRoot, patternList);
                if (output != null) ans.add(output);
                iter--;
            }
        }
        return ans;
    }

    private static String patternMatchingHelper(SentenceProcessor sentence, Map<SubSentWords, List<SubSentWords>> dict,
                                                SubSentWords subRoot, List<Map<String, Integer>> patternList) {
        List<String> out = new ArrayList<>();
        List<String> originalEncoding = sentence.getSentenceBreakdown().get(subRoot).stream().
                map(SubSentWords::getTrimmedEncoding).collect(Collectors.toList());
        List<Integer> matchedEntityPos;
        int multiCount = 0;
        for (int i = 0; i < 2; i++) {
            if (dict.containsKey(subRoot)) {
                List<String> lemmaWords = dict.get(subRoot).stream().map(SubSentWords::getLemma).collect(Collectors.toList());
                int endIndex = -1, startIndex = lemmaWords.size();
                for (String metaPattern : patternList.get(i).keySet()) {
                    List<Integer> nerIndices = check_subsequence(lemmaWords, originalEncoding, true,
                            metaPattern, nerTypes);
                    if (nerIndices != null) {
                        int newStart = nerIndices.get(0), newEnd = nerIndices.get(nerIndices.size() - 1);
                        boolean check1 = (i != 0) ? (newStart > endIndex) : (newStart >= endIndex);
                        boolean check2 = (i != 0) ? (newEnd < startIndex) : (newEnd <= startIndex);
                        if (check1 || check2) {
                            if (i == 0) multiCount++;
                            else {
                                if (multiCount > 0) break;
                            }
                            startIndex = Math.min(startIndex, newStart);
                            endIndex = Math.max(endIndex, newEnd);
                            matchedEntityPos = new ArrayList<>(nerIndices);
                            PatternInstance instance = new PatternInstance(sentence, subRoot, metaPattern, matchedEntityPos);
                            out.add(instance.toString());
                        }
                    }
                }
            }
        }
        return out.isEmpty() ? null : String.join("", out);
    }

    public static void call() throws Exception{
        initialization();
        String inputDirectory = inputFolder + data_name;
        String outputDirectory = outputFolder + data_name;

        boolean metadata_exists = new File(inputDirectory + "_dict.json").exists();
        boolean annotatedDataExists = new File(inputDirectory + "_annotated.txt").exists();
        if (!metadata_exists || !annotatedDataExists) {
            throw new Exception("Required Files not found.");
        }
        String suffix = "." + noOfLines + ".txt";
        boolean breakdownDataExists = new File(inputDirectory + "_sentenceBreakdown" + suffix).exists();
        if (load_sentenceBreakdownData && breakdownDataExists) {
            loadSentenceBreakdown();
        } else if (load_sentenceBreakdownData && !breakdownDataExists) {
            throw new IOException("Sentence Breakdown Data does not exist.");
        } else {
            buildDictionary();
            buildSentences();
            saveSentenceBreakdown();
        }
        int prevPatternCount = 0;
        int iterations = 1;

        List<Map<String, Integer>> patternList = new ArrayList<>();
        if (load_metapatternData) {
            logger.log(Level.INFO, "STARTING: Meta-Patterns Loading and Classification");
            loadPatternClassificationData();
            noOfPushUps = 1;
        } else {
            logger.log(Level.INFO, "STARTING: Iterative Frequent Pattern Mining followed by Hierarchical Pushups");
            frequent_pattern_mining(iterations);
        }

        while (iterations <= noOfPushUps && prevPatternCount < allPattern.size()) {
            prevPatternCount = allPattern.size();
            logger.log(Level.INFO, "STARTING: Hierarchical PushUps - Iteration " + iterations);
            for (SentenceProcessor sc : sentenceCollector) {
                hierarchicalPushUp(sc);
            }
            logger.log(Level.INFO, "COMPLETED: Hierarchical PushUps - Iteration " + iterations++);
            if (!load_metapatternData) frequent_pattern_mining(iterations);
        }
        savePatternClassificationData();
        logger.log(Level.INFO, "COMPLETED: Saving Meta-Patterns Classification Data");
        patternList.add(returnSortedPatternList(multiPattern));
        patternList.add(returnSortedPatternList(singlePattern));

        logger.log(Level.INFO, "STARTING: Pattern Matching");
        suffix = "." + noOfLines + "_" + minimumSupport + ".txt";
        BufferedWriter patternOutput = new BufferedWriter(new FileWriter(outputDirectory + "_patternOutput" + suffix));

        for (SentenceProcessor sp : sentenceCollector) {
            for (String output : patternFinding(sp, patternList)) {
                patternOutput.write(output);
            }
        }

        patternOutput.close();
        logger.log(Level.INFO, "COMPLETED: Pattern Matching");
    }
}