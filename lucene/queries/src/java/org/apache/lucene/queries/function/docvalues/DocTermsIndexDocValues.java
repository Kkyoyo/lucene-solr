/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.queries.function.docvalues;

import java.io.IOException;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.queries.function.FunctionValues;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.queries.function.ValueSourceScorer;
import org.apache.lucene.search.FieldCache;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRef;
import org.apache.lucene.util.UnicodeUtil;
import org.apache.lucene.util.mutable.MutableValue;
import org.apache.lucene.util.mutable.MutableValueStr;

/**
 * Serves as base class for FunctionValues based on DocTermsIndex.
 * @lucene.internal
 */
public abstract class DocTermsIndexDocValues extends FunctionValues {
  protected final SortedDocValues termsIndex;
  protected final ValueSource vs;
  protected final MutableValueStr val = new MutableValueStr();
  protected final BytesRef spare = new BytesRef();
  protected final CharsRef spareChars = new CharsRef();

  public DocTermsIndexDocValues(ValueSource vs, AtomicReaderContext context, String field) throws IOException {
    try {
      termsIndex = FieldCache.DEFAULT.getTermsIndex(context.reader(), field);
    } catch (RuntimeException e) {
      throw new DocTermsIndexException(field, e);
    }
    this.vs = vs;
  }

  protected abstract String toTerm(String readableValue);

  @Override
  public boolean exists(int doc) {
    return ordVal(doc) >= 0;
  }

  @Override
  public int ordVal(int doc) {
    return termsIndex.getOrd(doc);
  }

  @Override
  public int numOrd() {
    return termsIndex.getValueCount();
  }

  @Override
  public boolean bytesVal(int doc, BytesRef target) {
    termsIndex.get(doc, target);
    return target.length > 0;
  }

  @Override
  public String strVal(int doc) {
    termsIndex.get(doc, spare);
    if (spare.length == 0) {
      return null;
    }
    UnicodeUtil.UTF8toUTF16(spare, spareChars);
    return spareChars.toString();
  }

  @Override
  public boolean boolVal(int doc) {
    return exists(doc);
  }

  @Override
  public abstract Object objectVal(int doc);  // force subclasses to override

  @Override
  public ValueSourceScorer getRangeScorer(IndexReader reader, String lowerVal, String upperVal, boolean includeLower, boolean includeUpper) {
    // TODO: are lowerVal and upperVal in indexed form or not?
    lowerVal = lowerVal == null ? null : toTerm(lowerVal);
    upperVal = upperVal == null ? null : toTerm(upperVal);

    int lower = Integer.MIN_VALUE;
    if (lowerVal != null) {
      lower = termsIndex.lookupTerm(new BytesRef(lowerVal));
      if (lower < 0) {
        lower = -lower-1;
      } else if (!includeLower) {
        lower++;
      }
    }

    int upper = Integer.MAX_VALUE;
    if (upperVal != null) {
      upper = termsIndex.lookupTerm(new BytesRef(upperVal));
      if (upper < 0) {
        upper = -upper-2;
      } else if (!includeUpper) {
        upper--;
      }
    }

    final int ll = lower;
    final int uu = upper;

    return new ValueSourceScorer(reader, this) {
      @Override
      public boolean matchesValue(int doc) {
        int ord = termsIndex.getOrd(doc);
        return ord >= ll && ord <= uu;
      }
    };
  }

  @Override
  public String toString(int doc) {
    return vs.description() + '=' + strVal(doc);
  }

  @Override
  public ValueFiller getValueFiller() {
    return new ValueFiller() {
      private final MutableValueStr mval = new MutableValueStr();

      @Override
      public MutableValue getValue() {
        return mval;
      }

      @Override
      public void fillValue(int doc) {
        termsIndex.get(doc, mval.value);
        mval.exists = mval.value.bytes != SortedDocValues.MISSING;
      }
    };
  }

  /**
   * Custom Exception to be thrown when the DocTermsIndex for a field cannot be generated
   */
  public static final class DocTermsIndexException extends RuntimeException {

    public DocTermsIndexException(final String fieldName, final RuntimeException cause) {
      super("Can't initialize DocTermsIndex to generate (function) FunctionValues for field: " + fieldName, cause);
    }

  }


}
