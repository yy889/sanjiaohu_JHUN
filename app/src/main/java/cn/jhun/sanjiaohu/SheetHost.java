package cn.jhun.sanjiaohu;

import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 共享底层面板（{@link UiSheet}）需要的宿主能力。
 *
 * 主界面与电费查询页都会弹出同类面板，这里只暴露配色与几个基础控件工厂，
 * 让 UiSheet 不必绑死在某一个 Activity 上。
 */
interface SheetHost {
    ThemePalette palette();
    int ink();
    int muted();
    LinearLayout row();
    LinearLayout column();
    TextView label(String text,int size,int color,boolean bold);
    TextView themedButton(String text,Runnable action,boolean filled);
    GradientDrawable shape(int color,int radius);
    int dp(float value);
    int screenWidth();
}
