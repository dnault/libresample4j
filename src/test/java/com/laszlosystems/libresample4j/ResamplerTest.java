/******************************************************************************
 *
 * libresample4j
 * Copyright (c) 2009 Laszlo Systems, Inc. All Rights Reserved.
 *
 * libresample4j is a Java port of Dominic Mazzoni's libresample 0.1.3,
 * which is in turn based on Julius Smith's Resample 1.7 library.
 *      http://www-ccrma.stanford.edu/~jos/resample/
 *
 * License: LGPL -- see the file LICENSE.txt for more information
 *
 *****************************************************************************/
package com.laszlosystems.libresample4j;

import static java.lang.Math.abs;
import static java.lang.Math.min;
import static java.lang.Math.sin;
import static java.lang.Math.sqrt;

import java.io.PrintWriter;
import java.util.Random;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;

/**
 * Sadly, this isn't a unit test. It's a straight port of testresample.c from the original libresample,
 * preserving as much as possible of the original code and indentation.
 */
public class ResamplerTest {

    private interface RandomIntGenerator {
        int nextNonNegativeInt();
    }

    /**
     * Use this implementation if you want to compare the output of this program
     * with the output of libresample's "testresample" binary.
     */
    private static class NativeLibcRandomIntGenerator implements RandomIntGenerator {
        private interface CLibrary extends Library {
            CLibrary INSTANCE = (CLibrary)
                    Native.loadLibrary((Platform.isWindows() ? "msvcrt" : "c"), CLibrary.class);

            int rand();
        }

        public int nextNonNegativeInt() {
            return CLibrary.INSTANCE.rand();
        }
    }

    /**
     * Use this implementation if NativeLibcRandomIntGenerator doesn't work on your system for some reason.
     * The test output won't match the native "testresample" binary output, but maybe that's okay?
     */
    private static class JavaRandomIntGenerator implements RandomIntGenerator {
        private final Random random = new Random(0);

        public int nextNonNegativeInt() {
            return random.nextInt() & ~(1 << 31);
        }
    }

    private static final RandomIntGenerator randomIntGenerator = new NativeLibcRandomIntGenerator();
    // private static final RandomIntGenerator randomIntGenerator = new JavaRandomIntGenerator();

    private static final PrintWriter out = new PrintWriter(System.out);

    private static int rand() {
        return randomIntGenerator.nextNonNegativeInt();
    }

    private static void printf(String s, Object... args) {
        out.print(String.format(s, args));
        out.flush();
    }

    public static void main(String... args) throws Exception {
        int i, srclen, dstlen, ifreq;
        double factor;

        printf("\n*** Vary source block size*** \n\n");
        srclen = 10000;
        ifreq = 100;
        for(i=0; i<20; i++) {
           factor = ((rand() % 16) + 1) / 4.0;
           dstlen = (int)(srclen * factor + 10);
           runtest(srclen, (double)ifreq, factor, 64, dstlen);
           runtest(srclen, (double)ifreq, factor, 32, dstlen);
           runtest(srclen, (double)ifreq, factor, 8, dstlen);
           runtest(srclen, (double)ifreq, factor, 2, dstlen);
           runtest(srclen, (double)ifreq, factor, srclen, dstlen);
        }

        printf("\n*** Vary dest block size ***\n\n");
        srclen = 10000;
        ifreq = 100;
        for(i=0; i<20; i++) {
           factor = ((rand() % 16) + 1) / 4.0;
           runtest(srclen, (double)ifreq, factor, srclen, 32);
           dstlen = (int)(srclen * factor + 10);
           runtest(srclen, (double)ifreq, factor, srclen, dstlen);
        }

        printf("\n*** Resample factor 1.0, testing different srclen ***\n\n");
        ifreq = 40;
        for(i=0; i<100; i++) {
           srclen = (rand() % 30000) + 10;
           dstlen = (int)(srclen + 10);
           runtest(srclen, (double)ifreq, 1.0, srclen, dstlen);
        }

        printf("\n*** Resample factor 1.0, testing different sin freq ***\n\n");
        srclen = 10000;
        for(i=0; i<100; i++) {
           ifreq = ((int)rand() % 10000) + 1;
           dstlen = (int)(srclen * 10);
           runtest(srclen, (double)ifreq, 1.0, srclen, dstlen);
        }

        printf("\n*** Resample with different factors ***\n\n");
        srclen = 10000;
        ifreq = 100;
        for(i=0; i<100; i++) {
           factor = ((rand() % 64) + 1) / 4.0;
           dstlen = (int)(srclen * factor + 10);
           runtest(srclen, (double)ifreq, factor, srclen, dstlen);
        }
    }

    private static void runtest(int srclen, double freq, double factor, int srcblocksize, int dstblocksize)
{
  int expectedlen = (int)(srclen * factor);
  int dstlen = expectedlen + 1000;
  float[] src = new float[srclen+100];
  float[] dst = new float[dstlen+100];

  Resampler resampler;
  double sum, sumsq, err, rmserr;
  int i, out, o, srcused, errcount, rangecount;
  int statlen, srcpos, lendiff;
  int fwidth;

  printf("-- srclen: %d sin freq: %.1f factor: %.3f srcblk: %d dstblk: %d\n",
         srclen, freq, factor, srcblocksize, dstblocksize);

  for(i=0; i<srclen; i++)
     src[i] = (float) sin(i/freq);
  for(i=srclen; i<srclen+100; i++)
     src[i] = -99.0f;

  for(i=0; i<dstlen+100; i++)
     dst[i] = -99.0f;

  resampler = new Resampler(true, factor, factor);
  fwidth = resampler.getFilterWidth();
  out = 0;
  srcpos = 0;
  for(;;) {
     int srcBlock = min(srclen-srcpos, srcblocksize);
     boolean lastFlag = (srcBlock == srclen-srcpos);

     Resampler.Result result = resampler.process(factor, src, srcpos, srcBlock,
                          lastFlag,
                          dst, out, min(dstlen-out, dstblocksize));

     o = result.outputSamplesGenerated;
     srcused =  result.inputSamplesConsumed;

     //o = resampler.process(factor,
     //                     &src[srcpos], srcBlock,
     //                     lastFlag, &srcused,
     //                     &dst[out], min(dstlen-out, dstblocksize));

     srcpos += srcused;
     if (o >= 0)
        out += o;
     if (o < 0 || (o == 0 && srcpos == srclen))
        break;
  }

  if (o < 0) {
     printf("Error: resample_process returned an error: %d\n", o);
  }

  if (out <= 0) {
     printf("Error: resample_process returned %d samples\n", out);
     return;
  }

  lendiff = abs(out - expectedlen);
  if (lendiff > (int)(2*factor + 1.0)) {
     printf("   Expected ~%d, got %d samples out\n",
            expectedlen, out);
  }

  sum = 0.0;
  sumsq = 0.0;
  errcount = 0;

  /* Don't compute statistics on all output values; the last few
     are guaranteed to be off because it's based on far less
     interpolation. */
  statlen = out - fwidth;

  for(i=0; i<statlen; i++) {
     double diff = sin((i/freq)/factor) - dst[i];
     if (abs(diff) > 0.05) {
        if (errcount == 0)
           printf("   First error at i=%d: expected %.3f, got %.3f\n",
                  i, sin((i/freq)/factor), dst[i]);
        errcount++;
     }
     sum += abs(diff);
     sumsq += diff * diff;
  }

  rangecount = 0;
  for(i=0; i<statlen; i++) {
     if (dst[i] < -1.01 || dst[i] > 1.01) {
        if (rangecount == 0)
           printf("   Error at i=%d: value is %.3f\n", i, dst[i]);
        rangecount++;
     }
  }
  if (rangecount > 1)
     printf("   At least %d samples were out of range\n", rangecount);

  if (errcount > 0) {
     i = out - 1;
     printf("   i=%d:  expected %.3f, got %.3f\n",
            i, sin((i/freq)/factor), dst[i]);
     printf("   At least %d samples had significant error.\n", errcount);
  }
  err = sum / statlen;
  rmserr = sqrt(sumsq / statlen);
  printf("   Out: %d samples  Avg err: %f RMS err: %f\n", out, err, rmserr);
}

}
