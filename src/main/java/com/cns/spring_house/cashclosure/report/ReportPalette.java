package com.cns.spring_house.cashclosure.report;

import java.awt.Color;

/**
 * La paleta de la aplicacion, transcrita para el PDF.
 *
 * Los valores NO se sacaron del logo ni se inventaron: son las variables que ya
 * define el frontend Angular en `src/styles.css`, bloque `@theme` (tokens de
 * marca) y bloque `:root` (variables semanticas de modo claro). Cada constante
 * nombra abajo la variable de la que sale, para que un cambio de identidad se
 * pueda propagar leyendo una lista y no adivinando.
 *
 * Se usa deliberadamente el MODO CLARO aunque la aplicacion tenga modo oscuro:
 * un PDF se imprime y se archiva, y no puede cambiar de aspecto segun como
 * tuviera el navegador quien lo genero. Fondo blanco, tinta oscura y el
 * amarillo de marca reservado para encabezados, separadores, distintivos y
 * totales.
 */
final class ReportPalette {

    private ReportPalette() {
    }

    /** `--primary` / `--color-brand-yellow-500`: accion primaria e identidad. */
    static final Color PRIMARY = new Color(0xF8, 0xB8, 0x08);

    /** `--primary-hover` / `--color-brand-yellow-600`: filetes y remates. */
    static final Color PRIMARY_DARK = new Color(0xD9, 0x9A, 0x00);

    /** `--primary-text` / `--color-brand-black`: texto sobre el amarillo. */
    static final Color PRIMARY_TEXT = new Color(0x0B, 0x0B, 0x0B);

    /** `--text` / `--color-brand-charcoal`: texto principal. */
    static final Color TEXT = new Color(0x17, 0x17, 0x17);

    /** `--text-muted`: etiquetas, pie de pagina y texto de apoyo. */
    static final Color TEXT_MUTED = new Color(0x6F, 0x67, 0x5C);

    /** `--border`: filetes de tabla y separadores de bloque. */
    static final Color BORDER = new Color(0xE7, 0xE2, 0xD8);

    /** `--surface-muted`: fondo de cabeceras de seccion y filas alternas. */
    static final Color SURFACE_MUTED = new Color(0xF4, 0xF1, 0xEA);

    /** `--danger`: faltante de caja. */
    static final Color DANGER = new Color(0xE0, 0x00, 0x00);

    /** `--success`: caja cuadrada o sobrante. */
    static final Color SUCCESS = new Color(0x15, 0x80, 0x3D);

    /** `--warning`: distintivo de PREVISUALIZACION. */
    static final Color WARNING = new Color(0xF5, 0x9E, 0x0B);

    /** `--surface`: fondo de pagina. Un PDF se imprime: siempre blanco. */
    static final Color SURFACE = Color.WHITE;

}
