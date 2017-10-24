/*
 * Copyright (C) 2017 Cyandev
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.cyandev.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.support.annotation.IntDef;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.Property;
import android.util.TypedValue;
import android.view.View;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import me.cyandev.R;

/**
 * A user interface element that displays text to user and performs a character-unit animated
 * transition when its content changed. This is usually used for displaying changing numeral texts.
 */
public class BouncyText extends View {

    /**
     * Characters fly from the bottom to the top.
     */
    public final static int DIRECTION_UPWARD = 1;
    /**
     * Characters fall from the top to the bottom.
     */
    public final static int DIRECTION_DOWNWARD = -1;

    @IntDef({ DIRECTION_UPWARD, DIRECTION_DOWNWARD })
    @Retention(RetentionPolicy.SOURCE)
    @interface AnimationDirection {}

    private TextPaint mTextPaint;
    private Paint.FontMetrics mFontMetrics;

    private CharSequence mText = "";

    private List<CharacterInfo> mPrimaryInfos = new ArrayList<>();
    private List<CharacterInfo> mTransientInfos = new ArrayList<>();
    private RectF mBounds;

    private int mAnimationStagger = 45;
    private int mAnimationDuration = 450;
    private int mAnimationDirection = DIRECTION_UPWARD;
    private int mRunningAnimatorCount = 0;
    private boolean mEndingAnimators = false;
    private List<Animator> mActiveAnimators = new ArrayList<>();

    /** Prepared for measurements to avoid frequent allocation. */
    private Rect mTmpRect = new Rect();
    /** A char array with capacity of one char, avoiding frequent allocation.
     * This is frequently used in Canvas drawing operation. */
    private char[] mSingleCharArray = new char[1];

    private Handler mH = new Handler();
    private SimplePool<CharacterInfo> mInfoPool = new SimplePool<>(50);

    private Runnable mAnimatorsCleanupRunnable = new Runnable() {
        @Override
        public void run() {
            for (Animator animator : mActiveAnimators) {
                final ObjectAnimator oAnimator = (ObjectAnimator) animator;
                final CharacterInfo info = (CharacterInfo) oAnimator.getTarget();
                if (mTransientInfos.remove(info)) {
                    mInfoPool.release(info);
                }
            }

            mActiveAnimators.clear();
            mEndingAnimators = false;
        }
    };

    public BouncyText(Context context) {
        this(context, null);
    }

    public BouncyText(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BouncyText(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, R.style.Base_Widget_BouncyText);
    }

    public BouncyText(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr);

        final Resources res = getResources();
        final Resources.Theme theme = context.getTheme();

        final TypedArray a = theme.obtainStyledAttributes(attrs, R.styleable.BouncyText, 0,
                defStyleRes);

        mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.density = res.getDisplayMetrics().density;

        setTextSize(a.getDimensionPixelSize(R.styleable.BouncyText_textSize, 15));
        setTextColor(a.getColor(R.styleable.BouncyText_textColor, Color.BLACK));
        setText(a.getString(R.styleable.BouncyText_text));
        mAnimationDuration =
                a.getInteger(R.styleable.BouncyText_animationDuration, mAnimationDuration);
        mAnimationStagger =
                a.getInteger(R.styleable.BouncyText_animationStagger, mAnimationStagger);
        mAnimationDirection =
                a.getInt(R.styleable.BouncyText_animationDirection, mAnimationDirection);

        a.recycle();
    }

    /**
     * Sets the text size to given value, interpreted as "scaled pixel" units.
     * @param sp the scaled pixel size
     */
    public void setTextSize(int sp) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    /**
     * Sets the text size to given size, using given unit.
     * @param unit the unit defined in {@link TypedValue}
     * @param size the size
     */
    public void setTextSize(int unit, float size) {
        setTextSizeInternal(unit, size);
    }

    private void setTextSizeInternal(int unit, float size) {
        mTextPaint.setTextSize(
                TypedValue.applyDimension(unit, size, getResources().getDisplayMetrics()));

        // So far, we have to end current animations to swap the text size.
        endAnimators();

        for (CharacterInfo info : mPrimaryInfos) {
            mInfoPool.release(info);
        }
        mPrimaryInfos.clear();

        mPrimaryInfos = generateInfos(mText.toString());
        requestLayout();
        invalidate();
    }

    /**
     * Sets the text color to given color.
     * @param color the color
     */
    public void setTextColor(int color) {
        mTextPaint.setColor(color);
        invalidate();
    }

    /**
     * Sets the text to be displayed using a string resource identifier.
     * See also: {@link BouncyText#setText(CharSequence)}
     * @param resid the resource id
     */
    public void setText(@StringRes int resid) {
        setText(getContext().getString(resid));
    }

    /**
     * Sets the text to be displayed using a string, this will cause a flip fashion transition.
     * @param text the string
     */
    public void setText(CharSequence text) {
        text = text == null ? "" : text;

        if (mText != null && mText.equals(text)) {
            return;
        }

        mText = text;

        endAnimators();
        String textStr = text.toString();
        List<CharacterInfo> newInfos = generateInfos(textStr);
        do {
            if (mPrimaryInfos.size() == 0) {
                // Directly swap the list.
                mPrimaryInfos = newInfos;
                break;
            }

            if (newInfos.size() == 0) {
                // Directly recycle all infos in list.
                for (CharacterInfo info : mPrimaryInfos) {
                    mInfoPool.release(info);
                }
                mPrimaryInfos.clear();
            }

            performTransitions(newInfos);
        } while (false);

        requestLayout();
        invalidate();
    }

    /**
     * Sets the animation duration for single characters, the total duration will be this
     * duration multiplies the count of characters changed.
     * @param animationDuration the duration in milliseconds
     */
    public void setAnimationDuration(int animationDuration) {
        mAnimationDuration = animationDuration;
    }

    /**
     * Sets the stagger duration of animations, that is the interval between two character
     * animations.
     * @param stagger the duration in milliseconds
     */
    public void setAnimationStagger(int stagger) {
        mAnimationStagger = stagger;
    }

    /**
     * Sets the movement direction of animations to perform when text changed.
     * @param direction see {@link BouncyText#DIRECTION_UPWARD},
     *                      {@link BouncyText#DIRECTION_DOWNWARD}
     */
    public void setAnimationDirection(@AnimationDirection int direction) {
        if (direction != DIRECTION_UPWARD && direction != DIRECTION_DOWNWARD) {
            throw new IllegalArgumentException("Unsupported direction given.");
        }

        mAnimationDirection = direction;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        ensureBoundsRect();
        final int suggestedWidth = (int) mBounds.width();
        final int suggestedHeight = (int) mBounds.height();

        setMeasuredDimension(resolveSize(suggestedWidth, widthMeasureSpec),
                resolveSize(suggestedHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mFontMetrics != null) {
            // For performance, only rebuild metrics when it is not existed.
            ensureFontMetrics();
        }
        final float yAdjust = (getHeight() + mFontMetrics.ascent - mFontMetrics.descent) / 2.f
                - mFontMetrics.ascent;

        final int gState = canvas.save();
        canvas.translate(0, yAdjust);

        drawInfoList(canvas, mPrimaryInfos);
        drawInfoList(canvas, mTransientInfos);

        canvas.restoreToCount(gState);

        if (mRunningAnimatorCount > 0) {
            postInvalidate();
        } else if (!mEndingAnimators) {
            mH.post(mAnimatorsCleanupRunnable);
        }
    }

    private void drawInfoList(Canvas canvas, List<CharacterInfo> infos) {
        for (CharacterInfo info : infos) {
            mSingleCharArray[0] = info.ch;
            canvas.drawText(mSingleCharArray, 0, 1, info.x, info.y, mTextPaint);
        }
    }

    private void ensureFontMetrics() {
        if (mFontMetrics == null) {
            mFontMetrics = new Paint.FontMetrics();
        }
        mTextPaint.getFontMetrics(mFontMetrics);
    }

    private void ensureBoundsRect() {
        ensureFontMetrics();
        final float height = mFontMetrics.bottom - mFontMetrics.top;

        if (mBounds != null) {
            return;
        }

        float accWidth = 0;

        for (int i = 0, s = mPrimaryInfos.size(); i < s; i++) {
            CharacterInfo info = mPrimaryInfos.get(i);
            accWidth += info.w;
        }

        mBounds = new RectF(0, 0, accWidth, height);
    }

    private void performTransitions(List<CharacterInfo> newInfos) {
        ensureFontMetrics();
        final float height = mFontMetrics.bottom - mFontMetrics.top;
        final float directionFactor = mAnimationDirection;

        int i, j;
        int totalDelay = 0;

        boolean shorter = newInfos.size() < mPrimaryInfos.size();

        // Transit all different characters.
        for (i = mPrimaryInfos.size() - 1, j = newInfos.size() - 1; i >= 0 && j >= 0; i--, j--) {
            // True if the new info can be replaced by the old info, thus we can recycle
            // the new info.
            boolean removeMark = false;

            if (mPrimaryInfos.get(i).ch != newInfos.get(j).ch) {
                // Animate out
                CharacterInfo info = mPrimaryInfos.get(i);
                mTransientInfos.add(info);
                createAndStartAnimator(info, CharacterInfo.Y, false, -height * directionFactor,
                        totalDelay);

                // Animate in
                info = newInfos.get(j);
                mPrimaryInfos.set(i, info);
                createAndStartAnimator(info, CharacterInfo.Y, true, height * directionFactor,
                        totalDelay);
            } else {
                removeMark = true;
            }

            if (mPrimaryInfos.get(i).x != newInfos.get(j).x) {
                CharacterInfo oldInfo = mPrimaryInfos.get(i);
                CharacterInfo newInfo = newInfos.get(j);
                createAndStartAnimator(oldInfo, CharacterInfo.X, false, newInfo.x - oldInfo.x,
                        totalDelay);
            }

            if (removeMark) {
                mInfoPool.release(newInfos.remove(j));
            }

            totalDelay += mAnimationStagger;
        }

        // Remove characters if needed.
        if (shorter) {
            // Invalidate the bounds due to text length changing.
            mBounds = null;

            for (; i >= 0; i--) {
                CharacterInfo info = mPrimaryInfos.remove(i);
                mTransientInfos.add(info);
                createAndStartAnimator(info, CharacterInfo.Y, false, -height * directionFactor,
                        totalDelay);
            }

            return;
        }

        // Insert characters if needed.
        if (j >= 0) {
            mBounds = null;

            for (; j >= 0; j--) {
                CharacterInfo info = newInfos.get(j);
                mPrimaryInfos.add(0, info);
                createAndStartAnimator(info, CharacterInfo.Y, true, height * directionFactor,
                        totalDelay);

                totalDelay += mAnimationStagger;
            }
        }
    }

    private void endAnimators() {
        for (Animator animator : mActiveAnimators) {
            animator.end();
        }

        mRunningAnimatorCount = 0;
        mEndingAnimators = true;

        mAnimatorsCleanupRunnable.run();
        invalidate();
    }

    /**
     * Create a animator for character moving and start it immediately.
     * @param info the character to be animated
     * @param in whether to initiate a transition-in animation
     * @param delta the delta for start/end state
     * @param delay the delay of animation
     */
    private void createAndStartAnimator(CharacterInfo info, Property<CharacterInfo, Float> prop,
                                            boolean in, float delta, int delay) {
        final float startValue;
        final float endValue;
        if (in) {
            endValue = prop.get(info);
            startValue = endValue + delta;
        } else {
            startValue = prop.get(info);
            endValue = startValue + delta;
        }

        prop.set(info, startValue);

        Animator animator = ObjectAnimator.ofFloat(info, prop, startValue, endValue);
        animator.setDuration(mAnimationDuration);
        animator.setStartDelay(delay);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mRunningAnimatorCount--;
            }
        });

        mActiveAnimators.add(animator);
        animator.start();
        mRunningAnimatorCount++;
    }

    private List<CharacterInfo> generateInfos(String text) {
        List<CharacterInfo> infos = new ArrayList<>();
        final char[] chars = text.toCharArray();

        float x = 0;

        final float[] widths = new float[text.length()];
        mTextPaint.getTextWidths(text, widths);

        for (int i = 0; i < chars.length; i++) {
            mTextPaint.getTextBounds(chars, i, 1, mTmpRect);
            CharacterInfo info = acquireInfo();
            info.set(chars[i], x, 0, widths[i], 1);
            x += widths[i];
            infos.add(info);
        }

        return infos;
    }

    /**
     * Returns a pooled info object or create a new one when pool is empty.
     */
    private CharacterInfo acquireInfo() {
        CharacterInfo info = mInfoPool.acquire();
        if (info == null) {
            info = new CharacterInfo();
        }
        return info;
    }

    /**
     * A structure that records a series of into for drawing a single char.
     *
     * Note that the y coordinate stands for the "baseline" of a glyph, for
     * more information see
     * {@see <a href="https://developer.android.com/reference/android/graphics/Paint.FontMetrics.html">
     * Paint.FontMetrics</a>}.
     */
    private static class CharacterInfo {
        char ch;
        float x;
        float y;
        float w;  // width
        float a;  // alpha

        final static Property<CharacterInfo, Float> X =
                new Property<CharacterInfo, Float>(Float.class, "x") {
                    @Override
                    public void set(CharacterInfo object, Float value) {
                        object.x = value;
                    }

                    @Override
                    public Float get(CharacterInfo object) {
                        return object.x;
                    }
                };

        final static Property<CharacterInfo, Float> Y =
                new Property<CharacterInfo, Float>(Float.class, "y") {
                    @Override
                    public void set(CharacterInfo object, Float value) {
                        object.y = value;
                    }

                    @Override
                    public Float get(CharacterInfo object) {
                        return object.y;
                    }
                };

        void set(char ch, float x, float y, float w, float a) {
            this.ch = ch;
            this.x = x;
            this.y = y;
            this.w = w;
            this.a = a;
        }
    }

    private static class SimplePool<T> {
        final Object[] mPool;

        int mPoolSize = 0;

        SimplePool(int poolSize) {
            if (poolSize <= 0) {
                throw new IllegalArgumentException("The pool size must be larger than 0");
            }
            mPool = new Object[poolSize];
        }

        @SuppressWarnings("unchecked")
        T acquire() {
            if (mPoolSize > 0) {
                final int lastPooledIndex = mPoolSize - 1;
                T instance = (T) mPool[lastPooledIndex];
                mPool[lastPooledIndex] = null;
                mPoolSize--;
                return instance;
            }
            return null;
        }

        boolean release(T instance) {
            if (mPoolSize < mPool.length) {
                mPool[mPoolSize++] = instance;
                return true;
            }
            return false;
        }
    }

}
