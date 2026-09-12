import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApiResponse } from './api.service';

export interface ExpedienteMonitorDto {
  id: number;
  numeroExpediente: string;
  numeroAnio: string | null;
  cliente: string | null;
  juzgado: string | null;
  materia: string | null;
  estadoProcesal: string | null;
  nivelRiesgo: string | null;
  abogado: string | null;
  movimientoId: number | null;
  tipoMovimiento: string | null;
  fechaMovimiento: string | null;
  descripcion: string | null;
  resultado: string | null;
  proximaAccion: string | null;
  responsableMovimiento: string | null;
}

export interface AlertaMonitorDto {
  id: number;
  mensaje: string;
  fechaAlerta: string;
  dias: number;
  estado: string;
  tipoAlerta: string;
  critica: boolean;
  expedienteCodigo: string | null;
}

export interface SerieDiaDto {
  etiqueta: string;
  cantidad: number;
}

export interface JuzgadoMonitorDto {
  juzgado: string;
  cantidad: number;
}

export interface MonitorPanelDto {
  expedientes: ExpedienteMonitorDto[];
  alertasProximas: AlertaMonitorDto[];
  evolucionMovimientos: SerieDiaDto[];
  juzgadosActividad: JuzgadoMonitorDto[];
  juzgados: string[];
}

export interface MonitorFiltros {
  juzgado?: string;
  fechaDesde?: string;
  fechaHasta?: string;
  tipo?: string;
  estado?: string;
}

@Injectable({ providedIn: 'root' })
export class MonitorService {
  constructor(private http: HttpClient) {}

  panel(filtros: MonitorFiltros = {}): Observable<ApiResponse<MonitorPanelDto>> {
    let params = new HttpParams();
    for (const [k, v] of Object.entries(filtros)) {
      if (v) params = params.set(k, v);
    }
    return this.http.get<ApiResponse<MonitorPanelDto>>(`${environment.apiUrl}/monitor/panel`, { params });
  }
}