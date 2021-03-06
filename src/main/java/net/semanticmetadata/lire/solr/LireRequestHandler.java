/*
 * This file is part of the LIRE project: http://www.semanticmetadata.net/lire
 * LIRE is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * LIRE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LIRE; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * We kindly ask you to refer the any or one of the following publications in
 * any publication mentioning or employing Lire:
 *
 * Lux Mathias, Savvas A. Chatzichristofis. Lire: Lucene Image Retrieval –
 * An Extensible Java CBIR Library. In proceedings of the 16th ACM International
 * Conference on Multimedia, pp. 1085-1088, Vancouver, Canada, 2008
 * URL: http://doi.acm.org/10.1145/1459359.1459577
 *
 * Lux Mathias. Content Based Image Retrieval with LIRE. In proceedings of the
 * 19th ACM International Conference on Multimedia, pp. 735-738, Scottsdale,
 * Arizona, USA, 2011
 * URL: http://dl.acm.org/citation.cfm?id=2072432
 *
 * Mathias Lux, Oge Marques. Visual Information Retrieval using Java and LIRE
 * Morgan & Claypool, 2013
 * URL: http://www.morganclaypool.com/doi/abs/10.2200/S00468ED1V01Y201301ICR025
 *
 * Copyright statement:
 * --------------------
 * (c) 2002-2013 by Mathias Lux (mathias@juggle.at)
 *     http://www.semanticmetadata.net/lire, http://www.lire-project.net
 */

package net.semanticmetadata.lire.solr;

import net.semanticmetadata.lire.imageanalysis.features.GlobalFeature;
import net.semanticmetadata.lire.imageanalysis.features.global.ColorLayout;
import net.semanticmetadata.lire.imageanalysis.features.global.EdgeHistogram;
import net.semanticmetadata.lire.indexers.hashing.BitSampling;
import net.semanticmetadata.lire.utils.ImageUtils;
import org.apache.commons.codec.binary.Base64;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.Filter;
import org.apache.solr.search.SolrIndexSearcher;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.*;

/**
 * This is the main LIRE RequestHandler for the Solr Plugin. It supports query by example using the indexed id,
 * an url or a feature vector. Furthermore, feature extraction and random selection of images are supported.
 *
 * @author Mathias Lux, mathias@juggle.at, 07.07.13
 */

public class LireRequestHandler extends RequestHandlerBase {
    //    private static HashMap<String, Class> fieldToClass = new HashMap<String, Class>(5);
    private long time = 0;
    private int countRequests = 0;
    private int defaultNumberOfResults = 60;
    /**
     * number of candidate results retrieved from the index. The higher this number, the slower,
     * the but more accurate the retrieval will be. 10k is a good value for starters.
     */
    private int numberOfCandidateResults = 10000;
    private static final int DEFAULT_NUMBER_OF_CANDIDATES = 10000;

    /**
     * The number of query terms that go along with the TermsFilter search. We need some to get a
     * score, the less the faster. I put down a minimum of three in the method, this value gives
     * the percentage of the overall number used (selected randomly).
     */
    private double numberOfQueryTerms = 0.33;
    private static final double DEFAULT_NUMBER_OF_QUERY_TERMS = 0.33;

    static {
        // one time hash function read ...
        try {
            BitSampling.readHashFunctions();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    @Override
    public void init(NamedList args) {
        super.init(args);
    }

    /**
     * Handles three types of requests.
     * <ol>
     * <li>search by already extracted images.</li>
     * <li>search by an image URL.</li>
     * <li>Random results.</li>
     * </ol>
     *
     * @param req
     * @param rsp
     * @throws Exception
     */
    @Override
    public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
        // (1) check if the necessary parameters are here
        if (req.getParams().get("hashes") != null) { // we are searching for hashes ...
            handleHashSearch(req, rsp); // not really supported, just here for legacy.
        } else if (req.getParams().get("url") != null) { // we are searching for an image based on an URL
            handleUrlSearch(req, rsp);
        } else if (req.getParams().get("id") != null) { // we are searching for an image based on an URL
            handleIdSearch(req, rsp);
        } else if (req.getParams().get("extract") != null) { // we are trying to extract from an image URL.
            handleExtract(req, rsp);
        } else { // lets return random results.
            handleRandomSearch(req, rsp);
        }
    }

    /**
     * Handles the get parameters id, field and rows.
     *
     * @param req
     * @param rsp
     * @throws IOException
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    private void handleIdSearch(SolrQueryRequest req, SolrQueryResponse rsp) throws IOException, InstantiationException, IllegalAccessException {
        SolrIndexSearcher searcher = req.getSearcher();
        try {
            TopDocs hits = searcher.search(new TermQuery(new Term("id", req.getParams().get("id"))), 1);
            // get the parameters
            String paramField = req.getParams().get("field", "cl_ha");
            numberOfQueryTerms = req.getParams().getDouble("accuracy", DEFAULT_NUMBER_OF_QUERY_TERMS);
            numberOfCandidateResults = req.getParams().getInt("candidates", DEFAULT_NUMBER_OF_CANDIDATES);
            int paramRows = req.getParams().getInt("rows", defaultNumberOfResults);

            // check singleton cache if the term stats can be cached.
            HashTermStatistics.addToStatistics(searcher, paramField);
            GlobalFeature queryFeature = (GlobalFeature) FeatureRegistry.getClassForHashField(paramField).newInstance();
            rsp.add("QueryField", paramField);
            rsp.add("QueryFeature", queryFeature.getClass().getName());
            if (hits.scoreDocs.length > 0) {
                // Using DocValues to get the actual data from the index.
                BinaryDocValues binaryValues = MultiDocValues.getBinaryValues(searcher.getIndexReader(), FeatureRegistry.getFeatureFieldName(paramField));
                if (binaryValues == null) {
                    rsp.add("Error", "Could not find the DocValues of the query document. Are they in the index? Id: " + req.getParams().get("id"));
                    // System.err.println("Could not find the DocValues of the query document. Are they in the index?");
                }
                BytesRef bytesRef;
                bytesRef = binaryValues.get(hits.scoreDocs[0].doc);
                queryFeature.setByteArrayRepresentation(bytesRef.bytes, bytesRef.offset, bytesRef.length);

                // Re-generating the hashes to save space (instead of storing them in the index)
                int[] hashes = BitSampling.generateHashes(queryFeature.getFeatureVector());
//                List<Term> termFilter = createTermFilter(hashes, paramField, numberOfQueryTerms); // term filter needed here ...
                doSearch(req, rsp, searcher, paramField, paramRows, null, createQuery(hashes, paramField, numberOfQueryTerms), queryFeature);
            } else {
                rsp.add("Error", "Did not find an image with the given id " + req.getParams().get("id"));
            }
        } catch (Exception e) {
            rsp.add("Error", "There was an error with your search for the image with the id " + req.getParams().get("id")
                    + ": " + e.getMessage());
        }
    }

    /**
     * Returns a random set of documents from the index. Mainly for testing purposes.
     *
     * @param req
     * @param rsp
     * @throws IOException
     */
    private void handleRandomSearch(SolrQueryRequest req, SolrQueryResponse rsp) throws IOException {
        SolrIndexSearcher searcher = req.getSearcher();
        DirectoryReader indexReader = searcher.getIndexReader();
        double maxDoc = indexReader.maxDoc();
        int paramRows = req.getParams().getInt("rows", defaultNumberOfResults);
        LinkedList list = new LinkedList();
        while (list.size() < paramRows) {
            HashMap m = new HashMap(2);
            Document d = indexReader.document((int) Math.floor(Math.random() * maxDoc));
            m.put("id", d.getValues("id")[0]);
            m.put("title", d.getValues("title")[0]);
            list.add(m);
        }
        rsp.add("docs", list);
    }

    /**
     * Searches for an image given by an URL. Note that (i) extracting image features takes time and
     * (ii) not every image is readable by Java.
     *
     * @param req
     * @param rsp
     * @throws IOException
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    private void handleUrlSearch(SolrQueryRequest req, SolrQueryResponse rsp) throws IOException, InstantiationException, IllegalAccessException {
        SolrParams params = req.getParams();
        String paramUrl = params.get("url");
        String paramField = req.getParams().get("field", "cl_ha");
        int paramRows = params.getInt("rows", defaultNumberOfResults);
        numberOfQueryTerms = req.getParams().getDouble("accuracy", DEFAULT_NUMBER_OF_QUERY_TERMS);
        numberOfCandidateResults = req.getParams().getInt("candidates", DEFAULT_NUMBER_OF_CANDIDATES);

        HashTermStatistics.addToStatistics(req.getSearcher(), paramField);

        GlobalFeature feat = null;
        int[] hashes = null;
        // wrapping the whole part in the try
        try {
            BufferedImage img = ImageIO.read(new URL(paramUrl).openStream());
            img = ImageUtils.trimWhiteSpace(img);
            // getting the right feature per field:
            if (FeatureRegistry.getClassForHashField(paramField) == null) // if the feature is not registered.
                feat = new ColorLayout();
            else {
                feat = (GlobalFeature) FeatureRegistry.getClassForHashField(paramField).newInstance();
            }
            feat.extract(img);
            hashes = BitSampling.generateHashes(feat.getFeatureVector());
        } catch (Exception e) {
            rsp.add("Error", "Error reading image from URL: " + paramUrl + ": " + e.getMessage());
            e.printStackTrace();
        }
        // search if the feature has been extracted.
        if (feat != null)
            doSearch(req, rsp, req.getSearcher(), paramField, paramRows, null, createQuery(hashes, paramField, numberOfQueryTerms), feat);
    }

    /**
     * Methods orders around the hashes already by docFreq removing those with docFreq == 0
     * @param req
     * @param rsp
     * @throws IOException
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    private void handleExtract(SolrQueryRequest req, SolrQueryResponse rsp) throws IOException, InstantiationException, IllegalAccessException {
        SolrParams params = req.getParams();
        String paramUrl = params.get("extract");
        String paramField = req.getParams().get("field", "cl_ha");
        GlobalFeature feat;
        // wrapping the whole part in the try
        try {
            BufferedImage img = ImageIO.read(new URL(paramUrl).openStream());
            img = ImageUtils.trimWhiteSpace(img);
            // getting the right feature per field:
            if (FeatureRegistry.getClassForHashField(paramField) == null) // if the feature is not registered.
                feat = new ColorLayout();
            else {
                feat = (GlobalFeature) FeatureRegistry.getClassForHashField(paramField).newInstance();
            }
            feat.extract(img);
            rsp.add("histogram", Base64.encodeBase64String(feat.getByteArrayRepresentation()));
            int[] hashes = BitSampling.generateHashes(feat.getFeatureVector());
            List<String> hashStrings = orderHashes(hashes, paramField);
            rsp.add("hashes", hashStrings);
        } catch (Exception e) {
            rsp.add("Error", "Error reading image from URL: " + paramUrl + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Search based on the given image hashes.
     *
     * @param req
     * @param rsp
     * @throws IOException
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    private void handleHashSearch(SolrQueryRequest req, SolrQueryResponse rsp) throws IOException, IllegalAccessException, InstantiationException {
        SolrParams params = req.getParams();
        SolrIndexSearcher searcher = req.getSearcher();
        // get the params needed:
        // hashes=x y z ...
        // feature=<base64>
        // field=<cl_ha|ph_ha|...>

        String[] hashStrings = params.get("hashes").trim().split(" ");
        byte[] featureVector = Base64.decodeBase64(params.get("feature"));
        String paramField = req.getParams().get("field", "cl_ha");
        int paramRows = params.getInt("rows", defaultNumberOfResults);
        numberOfQueryTerms = req.getParams().getDouble("accuracy", DEFAULT_NUMBER_OF_QUERY_TERMS);
        numberOfCandidateResults = req.getParams().getInt("candidates", DEFAULT_NUMBER_OF_CANDIDATES);

        HashTermStatistics.addToStatistics(req.getSearcher(), paramField); // caching the term statistics.
        // create list of terms to filter results by.
        LinkedList<Term> termFilter = new LinkedList<Term>();
        for (int i = 0; i < hashStrings.length; i++) {
            // be aware that the hashFunctionsFileName of the field must match the one you put the hashes in before.
            hashStrings[i] = hashStrings[i].trim();
            if (hashStrings[i].length() > 0) {
                termFilter.add(new Term(paramField, hashStrings[i].trim()));
//                System.out.println("** " + field + ": " + hashes[i].trim());
            }
        }

        // query feature
        GlobalFeature queryFeature = (GlobalFeature) FeatureRegistry.getClassForHashField(paramField).newInstance();
        queryFeature.setByteArrayRepresentation(featureVector);

        // get results:
        doSearch(req, rsp, searcher, paramField, paramRows, termFilter, new MatchAllDocsQuery(), queryFeature);
    }

    /**
     * Actual search implementation based on (i) hash based retrieval and (ii) feature based re-ranking.
     * @param req the SolrQueryRequest
     * @param rsp the response to write the data to
     * @param searcher the actual index searcher object to search the index
     * @param hashFieldName the name of the field the hashes can be found
     * @param maximumHits the maximum nuber of hits, the smaller the faster
     * @param termsForFiltering can be null
     * @param query the (Boolean) query for querying the candidates from the IndexSearcher
     * @param queryFeature the image feature used for re-ranking the results
     * @throws IOException
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
    private void doSearch(SolrQueryRequest req, SolrQueryResponse rsp, SolrIndexSearcher searcher, String hashFieldName,
                          int maximumHits, List<Term> termsForFiltering, Query query, GlobalFeature queryFeature)
            throws IOException, IllegalAccessException, InstantiationException {
        // temp feature instance
        GlobalFeature tmpFeature = queryFeature.getClass().newInstance();
        // Taking the time of search for statistical purposes.
        time = System.currentTimeMillis();

        TopDocs docs = searcher.search(query, numberOfCandidateResults);

        time = System.currentTimeMillis() - time;
        rsp.add("RawDocsCount", docs.scoreDocs.length + "");
        rsp.add("RawDocsSearchTime", time + "");
        // re-rank
        time = System.currentTimeMillis();
        TreeSet<CachingSimpleResult> resultScoreDocs = new TreeSet<CachingSimpleResult>();
        double maxDistance = -1f;
        double tmpScore;

        String featureFieldName = FeatureRegistry.getFeatureFieldName(hashFieldName);
        // iterating and re-ranking the documents.
        BinaryDocValues binaryValues = MultiDocValues.getBinaryValues(searcher.getIndexReader(), featureFieldName);
        BytesRef bytesRef;
        CachingSimpleResult tmpResult;
        for (int i = 0; i < docs.scoreDocs.length; i++) {
            // using DocValues to retrieve the field values ...
            bytesRef = binaryValues.get(docs.scoreDocs[i].doc);
            tmpFeature.setByteArrayRepresentation(bytesRef.bytes, bytesRef.offset, bytesRef.length);
            // Getting the document from the index.
            // This is the slow step based on the field compression of stored fields.
//            tmpFeature.setByteArrayRepresentation(d.getBinaryValue(name).bytes, d.getBinaryValue(name).offset, d.getBinaryValue(name).length);
            tmpScore = queryFeature.getDistance(tmpFeature);
            if (resultScoreDocs.size() < maximumHits) {
                resultScoreDocs.add(new CachingSimpleResult(tmpScore, searcher.doc(docs.scoreDocs[i].doc), docs.scoreDocs[i].doc));
                maxDistance = resultScoreDocs.last().getDistance();
            } else if (tmpScore < maxDistance) {
                // if it is nearer to the sample than at least one of the current set:
                // remove the last one ...
                tmpResult = resultScoreDocs.last();
                resultScoreDocs.remove(tmpResult);
                // set it with new values and re-insert.
                tmpResult.set(tmpScore, searcher.doc(docs.scoreDocs[i].doc), docs.scoreDocs[i].doc);
                resultScoreDocs.add(tmpResult);
                // and set our new distance border ...
                maxDistance = resultScoreDocs.last().getDistance();
            }
        }
        // Creating response ...
        time = System.currentTimeMillis() - time;
        rsp.add("ReRankSearchTime", time + "");
        LinkedList list = new LinkedList();
        for (Iterator<CachingSimpleResult> it = resultScoreDocs.iterator(); it.hasNext(); ) {
            CachingSimpleResult result = it.next();
            HashMap m = new HashMap(2);
            m.put("d", result.getDistance());
            // add fields as requested:
            if (req.getParams().get("fl") == null) {
                m.put("id", result.getDocument().get("id"));
                if (result.getDocument().get("title") != null)
                    m.put("title", result.getDocument().get("title"));
            } else {
                String fieldsRequested = req.getParams().get("fl");
                if (fieldsRequested.contains("score")) {
                    m.put("score", result.getDistance());
                }
                if (fieldsRequested.contains("*")) {
                    // all fields
                    for (IndexableField field : result.getDocument().getFields()) {
                        String tmpField = field.name();
                        if (result.getDocument().getFields(tmpField).length > 1) {
                            m.put(result.getDocument().getFields(tmpField)[0].name(), result.getDocument().getValues(tmpField));
                        } else if (result.getDocument().getFields(tmpField).length > 0) {
                            m.put(result.getDocument().getFields(tmpField)[0].name(), result.getDocument().getFields(tmpField)[0].stringValue());
                        }
                    }
                } else {
                    StringTokenizer st;
                    if (fieldsRequested.contains(","))
                        st = new StringTokenizer(fieldsRequested, ",");
                    else
                        st = new StringTokenizer(fieldsRequested, " ");
                    while (st.hasMoreElements()) {
                        String tmpField = st.nextToken();
                        if (result.getDocument().getFields(tmpField).length > 1) {
                            m.put(result.getDocument().getFields(tmpField)[0].name(), result.getDocument().getValues(tmpField));
                        } else if (result.getDocument().getFields(tmpField).length > 0) {
                            m.put(result.getDocument().getFields(tmpField)[0].name(), result.getDocument().getFields(tmpField)[0].stringValue());
                        }
                    }
                }
            }
//            m.put(field, result.getDocument().get(field));
//            m.put(field.replace("_ha", "_hi"), result.getDocument().getBinaryValue(field));
            list.add(m);
        }
        rsp.add("docs", list);
        // rsp.add("Test-name", "Test-val");
    }

    @Override
    public String getDescription() {
        return "LIRE Request Handler to add images to an index and search them. Search images by id, by url and by extracted features.";
    }

    @Override
    public String getSource() {
        return "http://lire-project.net";
    }

    @Override
    public NamedList<Object> getStatistics() {
        // Change stats here to get an insight in the admin console.
        NamedList<Object> statistics = super.getStatistics();
        statistics.add("Number of Requests", countRequests);
        return statistics;
    }

    /**
     * Makes a Boolean query out of a list of hashes by ordering them ascending using their docFreq and
     * then only using the most distinctive ones, defined by size in [0.1, 1], size=1 takes all.
     *
     * @param hashes
     * @param paramField
     * @param size in [0.1, 1]
     * @return
     */
    private BooleanQuery createQuery(int[] hashes, String paramField, double size) {
        size = Math.max(0.1, Math.min(size, 1d)); // clamp size.
        List<String> hList = orderHashes(hashes, paramField);
        int numHashes = (int) Math.min(hList.size(), Math.floor(hashes.length * size));
        // a minimum of 3 hashes ...
        if (numHashes < 3) numHashes = 3;

        BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
        for (int i = 0; i < numHashes; i++) {
            // be aware that the hashFunctionsFileName of the field must match the one you put the hashes in before.
            queryBuilder.add(new BooleanClause(new TermQuery(new Term(paramField, hList.get(i))), BooleanClause.Occur.SHOULD));
        }
        BooleanQuery query = queryBuilder.build();
        // this query is just for boosting the results with more matching hashes. We'd need to match it to all docs.
//        query.add(new BooleanClause(new MatchAllDocsQuery(), BooleanClause.Occur.SHOULD));
        return query;
    }

    /**
     * Sorts the hashes to put those first, that do not show up in a large number of documents
     * while deleting those that are not in the index at all. Meaning: terms sorted by docFreq ascending, removing
     * those with docFreq == 0
     *
     * @param hashes the int[] of hashes
     * @param paramField the field in the index.
     * @return
     */
    private List<String> orderHashes(int[] hashes, String paramField) {
        List<String> hList = new ArrayList<>(hashes.length);
        // creates a list of terms.
        for (int i = 0; i < hashes.length; i++) {
            hList.add(Integer.toHexString(hashes[i]));
        }
        // uses our predetermined hash term stats object to sort the list
        Collections.sort(hList, (o1, o2) -> HashTermStatistics.docFreq(paramField, o1) - HashTermStatistics.docFreq(paramField, o2));
        // removing those with zero entries but leaving at least three.
        while (HashTermStatistics.docFreq(paramField, hList.get(0)) < 1 && hList.size() > 3) hList.remove(0);
        return hList;
    }

    /**
     * This is used to create a TermsFilter ... should be used to select in the index based on many terms.
     * We just need to integrate a minimum query too, else we'd not get the appropriate results.
     * TODO: This is wrong.
     * @param hashes
     * @param paramField
     * @return
     */
    private List<Term> createTermFilter(int[] hashes, String paramField, double size) {
        List<String> hList = new ArrayList<>(hashes.length);
        // creates a list of terms.
        for (int i = 0; i < hashes.length; i++) {
            hList.add(Integer.toHexString(hashes[i]));
        }
        // uses our predetermined hash term stats object to sort the list
        Collections.sort(hList, (o1, o2) -> HashTermStatistics.docFreq(paramField, o1) - HashTermStatistics.docFreq(paramField, o2));
        // removing those with zero entries but leaving at least three.
        while (HashTermStatistics.docFreq(paramField, hList.get(0)) < 1 && hList.size() > 3) hList.remove(0);
        int numHashes = (int) Math.min(hList.size(), Math.floor(hashes.length * size));
        // a minimum of 3 hashes ...
        if (numHashes < 3) numHashes = 3;
        LinkedList<Term> termFilter = new LinkedList<Term>();
        for (int i = 0; i < numHashes; i++) {
            // be aware that the hashFunctionsFileName of the field must match the one you put the hashes in before.
            termFilter.add(new Term(paramField, Integer.toHexString(hashes[i])));
        }
        return termFilter;
    }
}
