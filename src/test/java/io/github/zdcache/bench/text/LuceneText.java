package io.github.zdcache.bench.text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.ByteBuffersDirectory;

final class LuceneText implements TextSubject {
    private static final String ID = "id";
    private static final String WHOLE = "whole";
    private static final String WORDS = "words";

    private final ByteBuffersDirectory directory = new ByteBuffersDirectory();
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private final Map<Long, Student> students = new HashMap<>();

    LuceneText() {
        try {
            writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()));
            searchers = new SearcherManager(writer, null);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static Document document(Student student) {
        Document document = new Document();
        document.add(new StringField(ID, Long.toString(student.id()), Field.Store.NO));
        document.add(new NumericDocValuesField(ID, student.id()));
        document.add(new StringField(WHOLE, student.name().toLowerCase(Locale.ROOT), Field.Store.NO));
        document.add(new TextField(WORDS, student.name(), Field.Store.NO));
        return document;
    }

    @Override public void load(List<Student> all) {
        try {
            for (Student student : all) {
                students.put(student.id(), student);
                writer.updateDocument(new Term(ID, Long.toString(student.id())), document(student));
            }
            writer.commit();
            searchers.maybeRefreshBlocking();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Override public void put(Student student) {
        try {
            students.put(student.id(), student);
            writer.updateDocument(new Term(ID, Long.toString(student.id())), document(student));
            searchers.maybeRefreshBlocking();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static Query query(TextQuery query) {
        String text = query.text();
        return switch (query.kind()) {
            case PREFIX -> new PrefixQuery(new Term(WHOLE, text));
            case WORDS, WORDS_RANKED -> all(text, word -> new PrefixQuery(new Term(WORDS, word)));
            case FUZZY_WORDS_ONE_EDIT -> all(text, word -> new FuzzyQuery(new Term(WORDS, word), 1));
            case FUZZY_WORDS -> all(text, word -> new FuzzyQuery(new Term(WORDS, word), TextQuery.editsFor(word)));
            case FUZZY_WHOLE_ONE_EDIT, FUZZY_WHOLE_TWO_EDITS -> new FuzzyQuery(new Term(WHOLE, text), query.maxEdits());
            case CONTAINS, CONTAINS_TWO -> new WildcardQuery(new Term(WHOLE, "*" + text + "*"));
        };
    }

    private static Query all(String text, java.util.function.Function<String, Query> word) {
        BooleanQuery.Builder all = new BooleanQuery.Builder();
        for (String part : new java.util.LinkedHashSet<>(List.of(text.trim().split("\\s+")))) all.add(word.apply(part), BooleanClause.Occur.MUST);
        return all.build();
    }

    @Override public List<Student> search(TextQuery query) {
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                Query lucene = query(query);
                if (query.kind() == TextQuery.Kind.WORDS_RANKED) {
                    int count = searcher.count(lucene);
                    List<Student> found = new ArrayList<>(count);
                    if (count == 0) return found;
                    for (ScoreDoc hit : searcher.search(lucene, count).scoreDocs) found.add(students.get(idOf(searcher, hit.doc)));
                    return found;
                }
                return searcher.search(lucene, new Students());
            } finally { searchers.release(searcher); }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static long idOf(IndexSearcher searcher, int doc) throws IOException {
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        LeafReaderContext leaf = leaves.get(org.apache.lucene.index.ReaderUtil.subIndex(doc, leaves));
        NumericDocValues ids = leaf.reader().getNumericDocValues(ID);
        ids.advanceExact(doc - leaf.docBase);
        return ids.longValue();
    }

    private final class Collected implements Collector {
        final List<Student> found = new ArrayList<>();

        @Override public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
            NumericDocValues ids = context.reader().getNumericDocValues(ID);
            return new LeafCollector() {
                @Override public void setScorer(Scorable scorer) { }
                @Override public void collect(int doc) throws IOException {
                    if (ids.advanceExact(doc)) found.add(students.get(ids.longValue()));
                }
            };
        }

        @Override public ScoreMode scoreMode() { return ScoreMode.COMPLETE_NO_SCORES; }
    }

    private final class Students implements CollectorManager<Collected, List<Student>> {
        @Override public Collected newCollector() { return new Collected(); }

        @Override public List<Student> reduce(Collection<Collected> collectors) {
            List<Student> all = new ArrayList<>();
            for (Collected collector : collectors) all.addAll(collector.found);
            return all;
        }
    }

    @Override public Object root() { return this; }

    @Override public void close() {
        try {
            searchers.close();
            writer.close();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
