/*
 * Copyright 2011 Foundation for On-Line Genealogy, Inc.
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

package org.folg.gedcom.tools;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.folg.gedcom.model.*;
import org.folg.gedcom.parser.JsonParser;
import org.folg.gedcom.parser.ModelParser;
import org.folg.gedcom.parser.TreeParser;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.xml.sax.SAXParseException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.IntStream;

import static org.folg.gedcom.tools.Gedcom2Json.HeaderEnum.*;

/**
 * User: Dallan
 * Date: 1/1/12
 */
public class Gedcom2Json {
    @Option(name = "-i", required = true, usage = "gedcom file in")
    private File gedcomIn;

    @Option(name = "-o", required = false, usage = "json file out")
    private File jsonOut;

    @Option(name = "-t", required = false, usage = "use tree parser (use model parser by default)")
    private boolean useTreeParser = false;

    public static void main(String[] args) throws SAXParseException, IOException {
        Gedcom2Json self = new Gedcom2Json();
        CmdLineParser parser = new CmdLineParser(self);
        try {
            parser.parseArgument(args);
            self.doMain();
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
        }
    }

    private static boolean containsHanScript(String s) {
        String CHINESE_COMMON_SYMBOLS_RANGE = "\\u4E00-\\u9FFF";
        String CHINESE_CJK_SYMBOLS_RANGE = "\\u3000-\\u303F";
        String CHINESE_RARE_SYMBOLS_RANGE = "\\u3400-\\u4DBF\\U00020000-\\U0002A6DF\\U0002A700-\\U0002B73F\\U0002A740-\\U0002B81F\\U0002B820-\\U0002CEAF";

//        if (s.matches("[\\u4E00-\\u9FA5]+")) {
//            System.out.println("is Chinese");
//        }
        return s.codePoints().anyMatch(
                codepoint ->
                        Character.UnicodeScript.of(codepoint) == Character.UnicodeScript.HAN);
    }

    private static String[] headers() {
        return Arrays.toString(HeaderEnum.values()).replaceAll("^.|.$", "").split(", ");
    }

    //returns only Han char from the given string
    private String extractHanScriptfrom(String str) {
        if (str == null)
            return "";
        IntStream intStream = str.codePoints();
        String filtered = intStream.filter(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN || ch == 47)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        if (filtered.equals("/"))
            filtered = "";
        return filtered;
    }

    /**
     * build family lineages
     *
     * @param gedcom instance
     * @return a Map of linage indexed by ?
     */
    private Map<String, List<Person>> buildFamilyTree(Gedcom gedcom) {
        Set<Family> processedFamily = new HashSet<Family>();
        Map<String, List<Person>> lineageMap = new HashMap<>();
        for (Family family : gedcom.getFamilies()) {
            if (processedFamily.contains(family))
                continue;
            processedFamily.add(family);
            List<Person> lineage = new ArrayList<Person>();
            List<Person> husbandList = family.getHusbands(gedcom);
            if (husbandList.size() == 0)
                continue;
            //we assume each family has only one husband - it is the case in Philip Tan's ged
            Person familyHusband = family.getHusbandRefs().get(0).getPerson(gedcom);
            lineage.add(familyHusband);
            boolean noMoreAncester = true;
            while (familyHusband.getParentFamilies(gedcom).size() > 0 && noMoreAncester) {
                Family parentFamily = familyHusband.getParentFamilies(gedcom).get(0);
                processedFamily.add(parentFamily);
                husbandList = parentFamily.getHusbands(gedcom);
                if (husbandList.size() == 0) {
                    noMoreAncester = false;
                    continue;
                }
                Person parentHusband = parentFamily.getHusbands(gedcom).get(0);
                lineage.add(parentHusband);
                familyHusband = parentHusband;
            }
            lineageMap.put(lineage.get(0).getNames().get(0).getValue(), lineage);
            //stop at first Philip Tan's family 100 generation !
            //return lineageMap;
        }
        return lineageMap;
    }

    private void doMain() throws SAXParseException, IOException {
        String json;
        JsonParser jsonParser = new JsonParser();
        if (useTreeParser) {
            TreeParser treeParser = new TreeParser();
            List<GedcomTag> gedcomTags = treeParser.parseGedcom(gedcomIn);
            json = jsonParser.toJson(gedcomTags);
        } else {
            ModelParser modelParser = new ModelParser();
            long startTime = System.nanoTime();
            Gedcom gedcom = modelParser.parseGedcom(gedcomIn);
            long endTime = System.nanoTime();
            long duration = (endTime - startTime) / 1000000;
            gedcom.createIndexes();
            //checkForFamilyWithoutHusband(gedcom);
            //checkForExistingParentFamilies(gedcom);
            //checkForExistingMultipleHustbandInFamilies(gedcom);
            Map<String, List<Person>> lineageMap = this.buildFamilyTree(gedcom);
//            printOutLineageNames(lineageMap);
            Set<LineageMap> extract = this.extractGenerationsFromLineages(lineageMap);
            this.createCSVFile(extract);
            // get first family's husband
            //String ref = gedcom.getFamilies().get(0).getHusbandRefs().get(0).getRef();
            //I5465477880020118059 Chee Lin /TAN 陳/
            //gedcom.getPerson(ref);
            json = jsonParser.toJson(gedcom);
        }
        if (jsonOut != null) {
            PrintWriter writer = new PrintWriter(jsonOut);
            writer.println(json);
            writer.close();
        }
//      else {
//         System.out.println(json);
//      }
    }

    private Set<LineageMap> extractGenerationsFromLineages(Map<String, List<Person>> lineageMap) {
        Set<LineageMap> finaldata = new TreeSet<>(new LineageMapSorter());
        int lineageLongerThanTwo = 0;
        for (List<Person> lineage : lineageMap.values()) {
            if (lineage.size() < 2)
                continue;
            lineageLongerThanTwo++;
            int size = lineage.size();
            // get last person's name
            Person firstAncestor = lineage.get(size - 1);
            Person secondAncestor = lineage.get(size - 2);
            Person thirdAncestor = null;
            if (lineage.size() > 2)
                thirdAncestor = lineage.get(size - 3);
            finaldata.add(this.populateAncestors(firstAncestor, secondAncestor, thirdAncestor));
        }
        System.out.println("Found " + lineageLongerThanTwo + " lineage longer than 2");
        return finaldata;
    }

    private LineageMap populateAncestors(Person firstAncestor, Person secondAncestor,
                                         Person thirdAncestor) {
        LineageMap resultMap = new LineageMap(HeaderEnum.class);
        boolean hasAnte = thirdAncestor != null;
        //assume one name
        Name firstAncestorName = firstAncestor.getNames().get(0);
        Name secondAncestorName = secondAncestor.getNames().get(0);
        Name thirdAncestorName = hasAnte ? thirdAncestor.getNames().get(0) : null;

        resultMap.put(FIRST_ID, firstAncestor.getId());
        resultMap.put(FIRST__MARRNM, firstAncestorName.getMarriedName());
        resultMap.put(FIRST_GIVN, firstAncestorName.getGiven());
        firstAncestor.getEventsFacts().forEach(eventFact -> {
            if (eventFact.getTag().equals("BIRT"))
                resultMap.put(FIRST_BIRT, eventFact.getDate());
            if (eventFact.getTag().equals("DEAT"))
                resultMap.put(FIRST_DEAT, eventFact.getDate());
        });
        resultMap.put(SECOND_ID, secondAncestor.getId());
        resultMap.put(SECOND__MARRNM, secondAncestorName.getMarriedName());
        resultMap.put(SECOND_GIVN, secondAncestorName.getGiven());
        secondAncestor.getEventsFacts().forEach(eventFact -> {
            if (eventFact.getTag().equals("BIRT"))
                resultMap.put(SECOND_BIRT, eventFact.getDate());
            if (eventFact.getTag().equals("DEAT"))
                resultMap.put(SECOND_DEAT, eventFact.getDate());
        });

        resultMap.put(THIRD_ID, hasAnte ? thirdAncestor.getId() : "");
        resultMap.put(THIRD__MARRNM, hasAnte ? thirdAncestorName.getMarriedName() : "");
        resultMap.put(THIRD_GIVN, hasAnte ? thirdAncestorName.getGiven() : "");
        if (hasAnte) {
            thirdAncestor.getEventsFacts().forEach(eventFact -> {
                if (eventFact.getTag().equals("BIRT"))
                    resultMap.put(THIRD_BIRT, eventFact.getDate());
                if (eventFact.getTag().equals("DEAT"))
                    resultMap.put(THIRD_DEAT, eventFact.getDate());
            });
        }
        return resultMap;
    }

    private void printOutLineageNames(Map<String, List<Person>> lineageMap) {
        System.out.println("Printing Out lineage names by line (One line One lineage: ");
        for (List<Person> lineage : lineageMap.values()) {
            StringBuilder csvLineage = new StringBuilder();
            for (Person person : lineage) {
                csvLineage.append("'").append(person.getNames().get(0).getGiven()).append("', ");
            }
            System.out.println(csvLineage);
        }
    }

    private void checkForFamilyWithoutHusband(Gedcom gedcom) {
        System.out.println("Showing all families with NO Husband: ");
        for (Family family : gedcom.getFamilies()) {
            if (family.getHusbands(gedcom).size() == 0) {
                System.out.println("Family ID" + family.getId() + " has no husband");
            }
        }
    }

    private void checkForExistingParentFamilies(Gedcom gedcom) {
        System.out.println("Showing all families with more than one parentFamily:");
        for (Family family : gedcom.getFamilies()) {
            List<Person> personList = family.getHusbands(gedcom);
            if (personList.size() < 1)
                continue;
            List<Family> parentFamilies = family.getHusbands(gedcom).get(0).getParentFamilies(gedcom);
            if (parentFamilies.size() > 1) {
                System.out.println("Family ID" + family.getId());
                for (Family fam : parentFamilies)
                    System.out.println("parent family ID" + fam.getId());
            }
        }
    }

    private void checkForExistingMultipleHustbandInFamilies(Gedcom gedcom) {
        System.out.println("Showing all families with more than one husband:");
        for (Family family : gedcom.getFamilies()) {
            if (family.getHusbandRefs().size() > 1) {
                System.out.println("Family ID" + family.getId());
                for (SpouseRef husbandRef : family.getHusbandRefs())
                    System.out.println("SpouseRef ID" + husbandRef.getRef());
            }
        }
    }

    public void createCSVFile(Set<LineageMap> maps) throws IOException {
        FileWriter out = new FileWriter("lineage.csv");
        FileWriter outnohan = new FileWriter("lineage_chinese_filtered.csv");
        try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.withHeader(headers()))) {
            maps.forEach(lineageMap -> {
                List<String> values = new ArrayList<>();
                Arrays.asList(HeaderEnum.values()).forEach(header -> {
                    values.add(lineageMap.get(header));
                });
                try {
                    printer.printRecord(values);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
        try (CSVPrinter printer = new CSVPrinter(outnohan, CSVFormat.DEFAULT.withHeader(headers()))) {
            maps.forEach(lineageMap -> {
                List<String> values = new ArrayList<>();
                values.add(lineageMap.get(FIRST_ID));
                values.add(this.extractHanScriptfrom(lineageMap.get(FIRST__MARRNM)));
                values.add(this.extractHanScriptfrom(lineageMap.get(FIRST_GIVN)));
                values.add(lineageMap.get(FIRST_BIRT));
                values.add(lineageMap.get(FIRST_DEAT));
                values.add(lineageMap.get(SECOND_ID));
                values.add(this.extractHanScriptfrom(lineageMap.get(SECOND__MARRNM)));
                values.add(this.extractHanScriptfrom(lineageMap.get(SECOND_GIVN)));
                values.add(lineageMap.get(SECOND_BIRT));
                values.add(lineageMap.get(SECOND_DEAT));
                values.add(lineageMap.get(THIRD_ID));
                values.add(this.extractHanScriptfrom(lineageMap.get(THIRD__MARRNM)));
                values.add(this.extractHanScriptfrom(lineageMap.get(THIRD_GIVN)));
                values.add(lineageMap.get(THIRD_BIRT));
                values.add(lineageMap.get(THIRD_DEAT));
                //filter empty content
                if (!(values.get(1).isEmpty()
                        && values.get(2).isEmpty()
                        && values.get(6).isEmpty()
                        && values.get(7).isEmpty()
                        && values.get(11).isEmpty()
                        && values.get(12).isEmpty())
                ) {
                    try {
                        printer.printRecord(values);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    public enum HeaderEnum {
        FIRST_ID,
        FIRST__MARRNM,
        FIRST_GIVN,
        FIRST_BIRT,
        FIRST_DEAT,
        SECOND_ID,
        SECOND__MARRNM,
        SECOND_GIVN,
        SECOND_BIRT,
        SECOND_DEAT,
        THIRD_ID,
        THIRD__MARRNM,
        THIRD_GIVN,
        THIRD_BIRT,
        THIRD_DEAT,
    }

    private static class LineageMap extends EnumMap<HeaderEnum, String> {

        public LineageMap(Class<HeaderEnum> keyType) {
            super(keyType);
        }

        private int entryHashCode(HeaderEnum key) {
            return (key.hashCode() ^ this.get(key).hashCode());
        }

        @Override
        public int hashCode() {
            int h = 0;

            for (HeaderEnum key : HeaderEnum.values()) {
                if (key == FIRST_ID || key == SECOND_ID || key == THIRD_ID)
                    continue;
                if (null != this.get(key)) {
                    h += entryHashCode(key);
                }
            }

            return h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof LineageMap))
                return false;

            Map<?, ?> m = (Map<?, ?>) o;
            if (this.size() != m.size())
                return false;

            for (HeaderEnum key : HeaderEnum.values()) {
                if (key == FIRST_ID || key == SECOND_ID || key == THIRD_ID)
                    continue;
                if (null == this.get(key)) {
                    if (!((null == m.get(key)) && m.containsKey(key)))
                        return false;
                } else {
                    if (!this.get(key).equals(m.get(key)))
                        return false;
                }
            }

            return true;
        }

    }

    public static class LineageMapSorter implements Comparator<LineageMap> {
        @Override
        public int compare(LineageMap e1, LineageMap e2) {
            int first = e1.get(FIRST_ID).compareToIgnoreCase(e2.get(FIRST_ID));
            if (first == 0) {
                int second = e1.get(SECOND_ID).compareToIgnoreCase(e2.get(SECOND_ID));
                if (second == 0) {
                    String thirdid1 = e1.get(THIRD_ID);
                    String thirdid2 = e2.get(THIRD_ID);
                    if (null != thirdid1)
                        if (null != thirdid2)
                            return e1.get(THIRD_ID).compareToIgnoreCase(e2.get(THIRD_ID));
                        else
                            return -1;
                    else if (null != thirdid2)
                        return 1;
                    else
                        return 0;
                } else
                    return second;
            } else
                return first;
        }
    }
}
