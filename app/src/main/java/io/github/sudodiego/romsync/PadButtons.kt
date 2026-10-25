package io.github.sudodiego.romsync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/*
 * Los botones de Material con el mismo nombre, pero con el anillo de foco y el sonido de la app al llegar con el mando
 * (los de Material no los tenían: en Ajustes, Organizar o Partidas no sonaba nada ni se veía bien qué estaba elegido).
 * Se usan sin importar los de material3, así todos los botones de la app se comportan igual.
 */

private fun Modifier.pad(shape: Shape) = this.focusHighlight(shape, grow = 1.03f)

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ButtonDefaults.shape,
           colors: ButtonColors = ButtonDefaults.buttonColors(), elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
           border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
           interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.Button(onClick, modifier.pad(shape), enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun FilledTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ButtonDefaults.filledTonalShape,
                      colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(), elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
                      border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
                      interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.FilledTonalButton(onClick, modifier.pad(shape), enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ButtonDefaults.outlinedShape,
                   colors: ButtonColors = ButtonDefaults.outlinedButtonColors(), elevation: ButtonElevation? = null,
                   border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled), contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
                   interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.OutlinedButton(onClick, modifier.pad(shape), enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ButtonDefaults.textShape,
               colors: ButtonColors = ButtonDefaults.textButtonColors(), elevation: ButtonElevation? = null, border: BorderStroke? = null,
               contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding, interactionSource: MutableInteractionSource? = null,
               content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.TextButton(onClick, modifier.pad(shape), enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
               colors: IconButtonColors = IconButtonDefaults.iconButtonColors(), interactionSource: MutableInteractionSource? = null,
               content: @Composable () -> Unit) =
    androidx.compose.material3.IconButton(onClick, modifier.pad(RoundedCornerShape(99.dp)), enabled, colors, interactionSource, content)

@Composable
fun FilledTonalIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = IconButtonDefaults.filledShape,
                          colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(), interactionSource: MutableInteractionSource? = null,
                          content: @Composable () -> Unit) =
    androidx.compose.material3.FilledTonalIconButton(onClick, modifier.pad(shape), enabled, shape, colors, interactionSource, content)

@Composable
fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
               leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
               shape: Shape = FilterChipDefaults.shape, colors: SelectableChipColors = FilterChipDefaults.filterChipColors()) =
    androidx.compose.material3.FilterChip(selected, onClick, label, modifier.pad(shape), enabled, leadingIcon, trailingIcon, shape, colors)
