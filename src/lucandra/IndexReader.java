/**
 * Copyright 2009 T Jake Luciani
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package lucandra;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SliceRange;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.log4j.Logger;
import org.apache.lucene.analysis.SimpleAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldSelector;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermDocs;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.index.TermFreqVector;
import org.apache.lucene.index.TermPositions;
import org.apache.lucene.index.TermVectorMapper;
import org.apache.lucene.index.IndexWriter.MaxFieldLength;
import org.apache.lucene.search.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.store.RAMDirectory;

import solandra.SolandraFieldSelector;

public class IndexReader extends org.apache.lucene.index.IndexReader {

    private final static int numDocs = 1000000;
    
    private final static Directory mockDirectory = new RAMDirectory();
    static {
           
        try {
            new IndexWriter(mockDirectory, new SimpleAnalyzer(), true, MaxFieldLength.LIMITED);
        } catch (CorruptIndexException e) {
           throw new RuntimeException(e);
        } catch (LockObtainFailedException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final byte[] indexName;
    private final Cassandra.Iface client;
    
    private final ThreadLocal<Map<ByteBuffer, Integer>> docIdToDocIndex = new ThreadLocal<Map<ByteBuffer, Integer>>();
    private final ThreadLocal<Map<Integer, ByteBuffer>> docIndexToDocId = new ThreadLocal<Map<Integer, ByteBuffer>>();
    private final ThreadLocal<Map<Integer, Document>> documentCache = new ThreadLocal<Map<Integer, Document>>();
    private final ThreadLocal<AtomicInteger> docCounter = new ThreadLocal<AtomicInteger>();
    private final ThreadLocal<Map<Term, LucandraTermEnum>> termEnumCache = new ThreadLocal<Map<Term, LucandraTermEnum>>();
    private final ThreadLocal<Map<String,byte[]>> fieldNorms = new ThreadLocal<Map<String, byte[]>>();
    private final static ThreadLocal<Object> fieldCacheRefs  = new ThreadLocal<Object>();

    
    private static final Logger logger = Logger.getLogger(IndexReader.class);

    public IndexReader(String name, Cassandra.Iface client) {
        super();
        try {
            this.indexName = name.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("JVM does not support UTF-8");
        }
        this.client = client; 
    }

    public synchronized IndexReader reopen() throws CorruptIndexException, IOException {

        clearCache();

        return this;
    }

    public void clearCache() {
        
        if(docCounter.get() != null) docCounter.get().set(0);
        if(docIdToDocIndex.get() != null) docIdToDocIndex.get().clear();
        if(docIndexToDocId.get() != null) docIndexToDocId.get().clear();
        if(termEnumCache.get() != null) termEnumCache.get().clear();
        if(documentCache.get() != null) documentCache.get().clear();
        if(fieldNorms.get() != null) fieldNorms.get().clear();
    
        if (fieldCacheRefs.get() != null)
            fieldCacheRefs.set(UUID.randomUUID());
    }
    
    @Override
    public Object getFieldCacheKey() {
        
        Object ref = fieldCacheRefs.get();
        
        if(ref == null){           
            ref = UUID.randomUUID();
            fieldCacheRefs.set(ref);     
        }
             
        return ref;        
    }
    
    protected void doClose() throws IOException {
        clearCache();
    }

    
    protected void doCommit() throws IOException {
       clearCache();
    }

    
    protected void doDelete(int arg0) throws CorruptIndexException, IOException {

    }

    
    protected void doSetNorm(int arg0, String arg1, byte arg2) throws CorruptIndexException, IOException {

    }

    
    protected void doUndeleteAll() throws CorruptIndexException, IOException {

    }

    
    public int docFreq(Term term) throws IOException {

        LucandraTermEnum termEnum = getTermEnumCache().get(term);
        if (termEnum == null) {

            long start = System.currentTimeMillis();

            termEnum = new LucandraTermEnum(this);
            termEnum.skipTo(term);

            long end = System.currentTimeMillis();

            logger.debug("docFreq() took: " + (end - start) + "ms");

            getTermEnumCache().put(term, termEnum);
        }

        return termEnum.docFreq();
    }

    
    public Document document(int docNum, FieldSelector selector) throws CorruptIndexException, IOException {

        Document doc = getDocumentCache().get(docNum);

        if (doc != null){
            logger.debug("Found doc in cache");
            return doc;
        }

        ByteBuffer docId = getDocIndexToDocId().get(docNum);

        if (docId == null)
            return null;

        Map<ByteBuffer, ByteBuffer> keyMap = new ConcurrentSkipListMap<ByteBuffer, ByteBuffer>();

        byte[] docIdBytes = new byte[docId.remaining()];
        System.arraycopy(docId.array(), docId.position()+docId.arrayOffset(), docIdBytes, 0, docIdBytes.length);
        
        keyMap.put(CassandraUtils.hashKeyBytes(indexName, CassandraUtils.delimeterBytes , docIdBytes), docId);

        
        List<ByteBuffer> fieldNames = null;
        
        // Special field selector used to carry list of other docIds to cache in
        // Parallel for Solr Performance  
        if (selector != null && selector instanceof SolandraFieldSelector) {

            List<Integer> otherDocIds = ((SolandraFieldSelector) selector).getOtherDocsToCache();
            fieldNames = ((SolandraFieldSelector) selector).getFieldNames();
            
            logger.debug("Going to bulk load "+otherDocIds.size()+" documents");
            
            for (Integer otherDocNum : otherDocIds) {
                if (otherDocNum == docNum)
                    continue;

                if (getDocumentCache().containsKey(otherDocNum))
                    continue;

                ByteBuffer docKey = getDocIndexToDocId().get(otherDocNum);
                
                byte[] docKeyBytes = new byte[docKey.remaining()];
                System.arraycopy(docKey.array(), docKey.position()+docKey.arrayOffset(), docKeyBytes, 0, docKeyBytes.length);
                
                
                if (docKey == null)
                    continue;

                keyMap.put(CassandraUtils.hashKeyBytes(indexName, CassandraUtils.delimeterBytes, docKeyBytes), docKey);
            }           
        }
        
        ColumnParent columnParent = new ColumnParent();
        columnParent.setColumn_family(CassandraUtils.docColumnFamily);

        SlicePredicate slicePredicate = new SlicePredicate();
        
        if (fieldNames == null || fieldNames.size() == 0) {
            // get all columns ( except this skips meta info )
            slicePredicate.setSlice_range(new SliceRange(CassandraUtils.emptyByteArray, CassandraUtils.finalTokenBytes, false, 100));
        } else {
            
            slicePredicate.setColumn_names(fieldNames);
        }

       
        long start = System.currentTimeMillis();

        try {
            Map<ByteBuffer, List<ColumnOrSuperColumn>> docMap = client.multiget_slice(Arrays.asList(keyMap.keySet().toArray(new ByteBuffer[]{})), columnParent, slicePredicate, ConsistencyLevel.ONE);
      
            if(keyMap.size() != docMap.size()){
                logger.warn("Missing documents in multiget_slice call");
            }
            
            for (Map.Entry<ByteBuffer, List<ColumnOrSuperColumn>> entry : docMap.entrySet()) {

                List<ColumnOrSuperColumn> cols = entry.getValue();

                if (cols == null) {
                    logger.warn("Missing document in multiget_slice for: " + 
                            new String(entry.getKey().array(),entry.getKey().position()+entry.getKey().arrayOffset(),entry.getKey().remaining(),"UTF-8"));
                    continue;
                }

                Document cacheDoc = new Document();

                for (ColumnOrSuperColumn col : cols) {

                    Field field = null;
                    ByteBuffer name = col.column.name;
                    ByteBuffer v    = col.column.value;
                    int vlimit = v.limit()+v.arrayOffset();

                    String fieldName = new String(name.array(), name.position()+name.arrayOffset(), name.remaining());

                    //Incase __META__ slips through
                    if(col.column.name.equals(CassandraUtils.documentMetaFieldBytes)){
                        logger.debug("Filtering out __META__ key");
                        continue;
                    }
                    
                    byte[] value;
                   
                    if (v.array()[vlimit - 1] != Byte.MAX_VALUE && v.array()[vlimit - 1] != Byte.MIN_VALUE) {
                        throw new CorruptIndexException("Lucandra field is not properly encoded: " + docNum + "(" + fieldName + ")");

                    } else if (v.array()[vlimit - 1] == Byte.MAX_VALUE) { // Binary
                        value = new byte[v.remaining() - 1];
                        System.arraycopy(v.array(), v.position()+v.arrayOffset(), value, 0, v.remaining()-1);

                        field = new Field(fieldName, value, Store.YES);
                        cacheDoc.add(field);
                    } else if (v.array()[vlimit - 1] == Byte.MIN_VALUE) { // String
                        value = new byte[v.remaining() - 1];
                        System.arraycopy(v.array(), v.position()+v.arrayOffset(), value, 0, v.remaining()-1);

                        // Check for multi-fields
                        String fieldString = new String(value, "UTF-8");

                        if (fieldString.indexOf(CassandraUtils.delimeter) >= 0) {
                            StringTokenizer tok = new StringTokenizer(fieldString, CassandraUtils.delimeter);
                            while (tok.hasMoreTokens()) {
                                field = new Field(fieldName, tok.nextToken(), Store.YES, Index.ANALYZED);
                                cacheDoc.add(field);
                            }
                        } else {

                            field = new Field(fieldName, fieldString, Store.YES, Index.ANALYZED);
                            cacheDoc.add(field);
                        }
                    }
                }
                
                //Mark the required doc
                int thisDocNum = getDocumentNumber(keyMap.get(entry.getKey()));
                
                if(thisDocNum == docNum)
                    doc = cacheDoc;
                
                getDocumentCache().put(thisDocNum,cacheDoc);
            }

            long end = System.currentTimeMillis();

            logger.debug("Document read took: " + (end - start) + "ms");

            return doc;

        } catch (Exception e) {
            throw new IOException(e);
        }

    }

    @Override
    public Collection getFieldNames(FieldOption fieldOption) {
        return Arrays.asList(new String[] {});
    }

    @Override
    public TermFreqVector getTermFreqVector(int docNum, String field) throws IOException {

        ByteBuffer docId = getDocIndexToDocId().get(docNum);

        TermFreqVector termVector = new lucandra.TermFreqVector(indexName, field, docId, client);

        return termVector;
    }

    @Override
    public void getTermFreqVector(int arg0, TermVectorMapper arg1) throws IOException {
        throw new RuntimeException();
    }

    @Override
    public void getTermFreqVector(int arg0, String arg1, TermVectorMapper arg2) throws IOException {

        throw new RuntimeException();

    }

    @Override
    public TermFreqVector[] getTermFreqVectors(int arg0) throws IOException {
        throw new RuntimeException();
    }

    @Override
    public boolean hasDeletions() {

        return false;
    }

    @Override
    public boolean isDeleted(int arg0) {

        return false;
    }

    @Override
    public int maxDoc() {
        // if (numDocs == null)
        // numDocs();

        return numDocs + 1;
    }

    @Override
    public byte[] norms(String field) throws IOException {
        return  getFieldNorms().get(field);
    }

    @Override
    public void norms(String arg0, byte[] arg1, int arg2) throws IOException {

        throw new RuntimeException("This operation is not supported");
        
    }

    @Override
    public int numDocs() {

        return numDocs;
    }

    @Override
    public TermDocs termDocs() throws IOException {
        return new LucandraTermDocs(this);
    }

    @Override
    public TermPositions termPositions() throws IOException {
        return new LucandraTermDocs(this);
    }

    @Override
    public TermEnum terms() throws IOException {
        return new LucandraTermEnum(this);
    }

    @Override
    public TermEnum terms(Term term) throws IOException {

        LucandraTermEnum termEnum = getTermEnumCache().get(term);
        
        if(termEnum == null)
            termEnum = new LucandraTermEnum(this);
        
        if( !termEnum.skipTo(term) ) //if found in the cache then reset, otherwise init.
            return EmptyTermEnum.INSTANCE;
        

        return termEnum;
    }

    public int addDocument(SuperColumn docInfo, String field) {

        ByteBuffer id =  docInfo.name;
        
        if(logger.isDebugEnabled()){
            try {
                logger.debug("adding docId "+ new String(id.array(), id.position()+id.arrayOffset(), id.remaining(),"UTF-8"));
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
        
        Integer idx = getDocIdToDocIndex().get(id);

        if (idx == null) {
            idx = getDocCounter().incrementAndGet();

            if (idx > numDocs)
                throw new IllegalStateException("numDocs reached");

            getDocIdToDocIndex().put(id, idx);
            getDocIndexToDocId().put(idx, id);
            
            Byte norm = null;
            for(Column c : docInfo.columns){
                if(c.name.equals(CassandraUtils.normsKeyBytes)){
                    if(c.value.remaining() != 1)
                        throw new IllegalStateException("Norm for field "+field+" must be a single byte");
                    
                    norm = c.value.array()[c.value.position()+c.value.arrayOffset()];
                }                 
            }
            
            if(norm == null)
                norm = Similarity.encodeNorm(1.0f);
            
            byte[] norms = getFieldNorms().get(field);
            

            if (norms == null) {

                norms = new byte[1024];

                norms[idx] = norm; 
            } else {

                // extend array
                if ((idx + 1) >= norms.length) {

                    byte[] _norms = new byte[(norms.length * 2) < numDocs ? (norms.length * 2) : (numDocs + 1)];
                    System.arraycopy(norms, 0, _norms, 0, norms.length);
                    norms = _norms;
                }

                // find next empty position
                norms[idx] = norm;

            }

            getFieldNorms().put(field, norms);            
        }

        return idx;
    }
    
    public int getDocumentNumber(ByteBuffer docId){
       
        return getDocIdToDocIndex().get(docId);
    }
    
    public ByteBuffer getDocumentId(int docNum) {
        return getDocIndexToDocId().get(docNum);
    }

    public byte[] getIndexName() {
        return indexName;
    }

    public Cassandra.Iface getClient() {
        return client;
    }

    public LucandraTermEnum checkTermCache(Term term) {
        return getTermEnumCache().get(term);
    }

    public void addTermEnumCache(Term term, LucandraTermEnum termEnum) {
        getTermEnumCache().put(term, termEnum);
    }

    @Override
    public Directory directory() {
        clearCache();
        
        return mockDirectory;
    }

    @Override
    public long getVersion() {
        return 1;
    }

    @Override
    public boolean isOptimized() {
       return true;
    }
    
    @Override
    public boolean isCurrent() {
       return true;
    }

    public Map<Integer, ByteBuffer> getDocIndexToDocId() {
        Map<Integer, ByteBuffer> c = docIndexToDocId.get();
        
        if(c == null){
            c = new HashMap<Integer,ByteBuffer>();
            docIndexToDocId.set(c);
        }
        
        return c;
    }
    
    private Map<ByteBuffer,Integer> getDocIdToDocIndex(){
        Map<ByteBuffer, Integer> c = docIdToDocIndex.get();
        
        if(c == null){
            c = new ConcurrentSkipListMap<ByteBuffer,Integer>();
            docIdToDocIndex.set(c);
        }
        
        return c;
    }
    
    private AtomicInteger getDocCounter(){
        AtomicInteger c = docCounter.get();
        
        if(c == null){
            c = new AtomicInteger(0);
            docCounter.set(c);
        }
        
        return c;
    }
    
    private Map<Term,LucandraTermEnum> getTermEnumCache(){
        Map<Term,LucandraTermEnum> c = termEnumCache.get();
    
        if(c == null){
            c = new HashMap<Term,LucandraTermEnum>();
            termEnumCache.set(c);
        }
        
        return  c;
    }
    
    private Map<Integer,Document> getDocumentCache(){
        Map<Integer,Document> c = documentCache.get();
    
        if(c == null){
            c = new HashMap<Integer,Document>();
            documentCache.set(c);
        }
        
        return c;
    }
    
    private Map<String,byte[]> getFieldNorms(){
        Map<String, byte[]> c = fieldNorms.get();
        
        if(c == null){
            c = new HashMap<String,byte[]>();
            fieldNorms.set(c);
        }
        
        return c;
    }
    
     private static class EmptyTermEnum extends TermEnum {
        private static final TermEnum INSTANCE = new EmptyTermEnum();

         private EmptyTermEnum() {
        }

        public boolean next() {
           return false;
        }

        public Term term() {
           return null;
        }

        public int docFreq() {
           return 0;
        }
       
        public void close() {
        }
     }
}
