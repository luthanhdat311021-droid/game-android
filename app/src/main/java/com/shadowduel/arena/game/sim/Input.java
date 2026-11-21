package com.shadowduel.arena.game.sim;

/** One tick of player input. mx: -100..100, my: -1/0/1, b: button bits (Const.BTN_*). */
public final class Input {
    public int seq;
    public int mx;
    public int my;
    public int b;

    public static final Input NEUTRAL = new Input();

    public Input() {
    }

    public Input(int mx, int my, int b) {
        this.mx = mx;
        this.my = my;
        this.b = b;
    }

    public Input copy() {
        Input i = new Input(mx, my, b);
        i.seq = seq;
        return i;
    }
}
