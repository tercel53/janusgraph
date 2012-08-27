package com.thinkaurelius.faunus.mapreduce.transform;

import com.thinkaurelius.faunus.FaunusEdge;
import com.thinkaurelius.faunus.FaunusElement;
import com.thinkaurelius.faunus.FaunusVertex;
import com.thinkaurelius.faunus.Tokens;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import groovy.lang.Closure;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.IOException;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class TransformMap {

    public static final String CLASS = Tokens.makeNamespace(TransformMap.class) + ".class";
    public static final String CLOSURE = Tokens.makeNamespace(TransformMap.class) + ".closure";
    private static final ScriptEngine engine = new GremlinGroovyScriptEngine();

    public enum Counters {
        VERTICES_PROCESSED,
        EDGES_PROCESSED
    }

    public static class Map extends Mapper<NullWritable, FaunusVertex, FaunusElement, Text> {

        private Closure closure;
        private boolean isVertex;

        @Override
        public void setup(final Mapper.Context context) throws IOException, InterruptedException {
            this.isVertex = context.getConfiguration().getClass(CLASS, Element.class, Element.class).equals(Vertex.class);
            try {
                this.closure = (Closure) engine.eval(context.getConfiguration().get(CLOSURE));
            } catch (final ScriptException e) {
                throw new IOException(e.getMessage(), e);
            }
        }

        private final Text textWritable = new Text();

        @Override
        public void map(final NullWritable key, final FaunusVertex value, final Mapper<NullWritable, FaunusVertex, FaunusElement, Text>.Context context) throws IOException, InterruptedException {
            if (this.isVertex) {
                if (value.hasPaths()) {
                    final Object result = this.closure.call(value);
                    this.textWritable.set(null == result ? Tokens.NULL : result.toString());
                    context.write(value, this.textWritable);
                    context.getCounter(Counters.VERTICES_PROCESSED).increment(1l);
                }
            } else {
                long edgesProcessed = 0;
                for (final Edge e : value.getEdges(Direction.OUT)) {
                    final FaunusEdge edge = (FaunusEdge) e;
                    if (edge.hasPaths()) {
                        final Object result = this.closure.call(edge);
                        this.textWritable.set(null == result ? Tokens.NULL : result.toString());
                        context.write(edge, this.textWritable);
                        edgesProcessed++;
                    }
                }
                context.getCounter(Counters.EDGES_PROCESSED).increment(edgesProcessed);
            }
        }
    }
}
