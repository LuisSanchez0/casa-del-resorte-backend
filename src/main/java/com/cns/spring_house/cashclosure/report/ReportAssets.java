package com.cns.spring_house.cashclosure.report;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * El logo corporativo, cargado UNA vez del classpath.
 *
 * El archivo original vivia solo en S3
 * (`assets/img/1724219599840ac50db490130a02cf08e9752.36303618.png`). Depender
 * de esa descarga en cada generacion habria atado un documento contable a que
 * un bucket respondiera: un corte de red dejaria el cierre sin logo o sin PDF.
 * Se incorporo como recurso de la aplicacion y se lee del classpath.
 *
 * La carga ocurre al arrancar y no al generar el primer PDF a proposito: si el
 * recurso faltara —un empaquetado mal hecho, un `resources` filtrado— el
 * contexto no levanta y se sabe en el despliegue, no cuando alguien intenta
 * cerrar la caja.
 */
@Component
public class ReportAssets {

    static final String LOGO_RESOURCE = "report/logo-la-casa-del-resorte.png";

    private byte[] logo;

    @PostConstruct
    void loadLogo() {
        this.logo = readLogo();
    }

    /**
     * Copia defensiva: los bytes se entregan a PDFBox, y un consumidor que
     * escribiera sobre el arreglo corromperia el logo de todos los PDF
     * siguientes hasta reiniciar la aplicacion.
     */
    public byte[] logo() {
        return logo.clone();
    }

    static byte[] readLogo() {
        ClassPathResource resource = new ClassPathResource(LOGO_RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo cargar el logo del reporte: " + LOGO_RESOURCE,
                    exception);
        }
    }

}
