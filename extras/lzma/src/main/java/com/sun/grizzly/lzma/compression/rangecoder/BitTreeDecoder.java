/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */

package com.sun.grizzly.lzma.compression.rangecoder;

/**
 * BitTreeDecoder
 *
 * @author Igor Pavlov
 */
public class BitTreeDecoder {

    short[] Models;
    int NumBitLevels;

    public BitTreeDecoder(int numBitLevels) {
        NumBitLevels = numBitLevels;
        Models = new short[1 << numBitLevels];
    }

    public void Init() {
        Decoder.InitBitModels(Models);
    }

    public int Decode(Decoder rangeDecoder) throws java.io.IOException {
        int m = 1;
        for (int bitIndex = NumBitLevels; bitIndex != 0; bitIndex--) {
            m = (m << 1) + rangeDecoder.DecodeBit(Models, m);
        }
        return m - (1 << NumBitLevels);
    }

    public int ReverseDecode(Decoder rangeDecoder) throws java.io.IOException {
        int m = 1;
        int symbol = 0;
        for (int bitIndex = 0; bitIndex < NumBitLevels; bitIndex++) {
            int bit = rangeDecoder.DecodeBit(Models, m);
            m <<= 1;
            m += bit;
            symbol |= (bit << bitIndex);
        }
        return symbol;
    }

    public static int ReverseDecode(short[] Models, int startIndex,
            Decoder rangeDecoder, int NumBitLevels) throws java.io.IOException {
        int m = 1;
        int symbol = 0;
        for (int bitIndex = 0; bitIndex < NumBitLevels; bitIndex++) {
            int bit = rangeDecoder.DecodeBit(Models, startIndex + m);
            m <<= 1;
            m += bit;
            symbol |= (bit << bitIndex);
        }
        return symbol;
    }
}