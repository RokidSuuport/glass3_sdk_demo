package com.rokid.glass.recycleview;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.rokid.glesse.R;


public class BaseViewHolder extends RecyclerView.ViewHolder  {
    public TextView itemTitle;
    TextView itemLabel;
    public ConstraintLayout itemContent;

    public BaseViewHolder(@NonNull View itemView) {
        super(itemView);
        itemContent = itemView.findViewById(R.id.item_content);
        itemLabel = itemView.findViewById(R.id.item_label);
        itemTitle = itemView.findViewById(R.id.item_title);
    }
}
